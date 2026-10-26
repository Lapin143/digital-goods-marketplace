package dgm.platform.identity.service;

import dgm.kit.keycloak.KeycloakException;
import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.identity.api.EmailChanger;
import dgm.platform.identity.domain.Actor;
import dgm.platform.identity.domain.EmailTakenException;
import dgm.platform.identity.domain.Roles;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.otp.crypto.AuditHasher;
import dgm.platform.identity.repository.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Применение подтверждённой смены e-mail (компонент {@code user-service}, SEQ-04, шаги 18 – 20, Р-169). Порядок (правило 2: вызов Keycloak не внутри
 * транзакции базы): проверка по базе без блокировок, вызов Keycloak (Admin REST, идемпотентно), затем одна транзакция {@code identity}: запись
 * учётной записи под блокировкой строки, прежний адрес в {@code previous_email} на 7 суток, событие {@code user.email-changed} и запись аудита с хешами
 * адреса до и после (соль пользователя, поле {@code email}, ADR-014) в Outbox.
 *
 * <p>Падение между Keycloak и транзакцией безопасно: повтор ставит в Keycloak то же значение и доделывает транзакцию, а второй вызов, дошедший
 * до блокировки позже первого, видит новый адрес и ничего не пишет, поэтому событие и запись аудита появляются один раз. Если адрес успели занять
 * между проверкой и транзакцией (гонка с регистрацией), Keycloak возвращается к прежнему адресу, насколько это удаётся, а ответ тот же нейтральный
 * 409 (INV-34). Исполнитель в журнале это оператор, принявший решение (его запись о начале смены сделана раньше, там и его адрес): завершает смену
 * платформа по коду покупателя, адреса исполнителя у этой записи нет.
 */
@Component
public final class AccountEmailChanger implements EmailChanger {

    /** Сколько прежний адрес хранится для письма о смене (решение 3 раздела 8 SEQ-04). */
    static final Duration PREVIOUS_KEPT = Duration.ofDays(7);
    static final int RETRY_AFTER_SECONDS = 30;
    private static final String EMAIL_FIELD = "email";
    private static final Logger LOG = LoggerFactory.getLogger(AccountEmailChanger.class);

    private final UserAccountRepository accounts;
    private final IdentityProvider provider;
    private final IdentityTransaction tx;
    private final AuditHasher hasher;
    private final Clock clock;

    public AccountEmailChanger(UserAccountRepository accounts, IdentityProvider provider, IdentityTransaction tx, AuditHasher hasher, Clock clock) {
        this.accounts = accounts;
        this.provider = provider;
        this.tx = tx;
        this.hasher = hasher;
        this.clock = clock;
    }

    @Override
    public boolean apply(UUID userId, String newEmail, UUID ticketId, Optional<UUID> operatorId) {
        UserAccount account = accounts.find(userId).filter(UserAccount::active).filter(a -> !a.anonymized())
                .orElseThrow(() -> new ProblemException(ProblemType.STATE_CONFLICT, "У пользователя нет активной учётной записи, адрес сменить нельзя."));
        if (account.email().equals(newEmail)) {
            return false;
        }
        if (accounts.emailExists(newEmail)) {
            throw emailNotAllowed();
        }
        setInKeycloak(userId, newEmail);
        try {
            return tx.execute(() -> write(userId, newEmail, ticketId, operatorId));
        } catch (EmailTakenException e) {
            restoreInKeycloak(userId, account.email());
            throw emailNotAllowed();
        }
    }

    /** Транзакция {@code identity}: запись, событие и аудит вместе. Адрес уже новый: другой вызов успел раньше, повторять нечего. */
    private boolean write(UUID userId, String newEmail, UUID ticketId, Optional<UUID> operatorId) {
        UserAccount locked = accounts.findForUpdate(userId).filter(UserAccount::active).filter(a -> !a.anonymized())
                .orElseThrow(() -> new ProblemException(ProblemType.STATE_CONFLICT, "У пользователя нет активной учётной записи, адрес сменить нельзя."));
        if (locked.email().equals(newEmail)) {
            return false;
        }
        Instant now = clock.instant();
        byte[] salt = accounts.auditSalt(userId).orElseThrow(() -> new IllegalStateException("У записи нет соли аудита: " + userId));
        if (!accounts.replaceEmail(userId, newEmail, now.plus(PREVIOUS_KEPT))) {
            return false;
        }
        Actor actor = operatorId.map(id -> new Actor(id, Roles.SUPPORT_OPERATOR, "")).orElse(Actor.SYSTEM);
        tx.publish(IdentityEvents.USER_TOPIC, IdentityEvents.USER_AGGREGATE, userId.toString(), IdentityEvents.EMAIL_CHANGED, 1,
                IdentityEvents.emailChanged(userId, ticketId, actor.id()));
        tx.publish(IdentityEvents.AUDIT_TOPIC, IdentityEvents.AUDIT_AGGREGATE, userId.toString(), IdentityEvents.AUDIT_EVENT, 1,
                IdentityEvents.audit(now, actor, IdentityEvents.AUDIT_EMAIL_CHANGED, userId,
                        List.of(AuditRecords.personalChange(EMAIL_FIELD, hasher.hash(salt, EMAIL_FIELD, locked.email()),
                                        hasher.hash(salt, EMAIL_FIELD, newEmail)),
                                IdentityEvents.change("ticketId", null, ticketId.toString()))));
        return true;
    }

    private void setInKeycloak(UUID userId, String email) {
        try {
            provider.setEmail(userId, email);
        } catch (KeycloakException e) {
            if (e.status() == 409) {
                throw emailNotAllowed();
            }
            // Текст ошибки не несёт адреса (клиент каркаса его не повторяет), но в журнал всё равно идёт только суть
            LOG.warn("Адрес пользователя {} не изменён в Keycloak: {}", userId, e.getMessage());
            throw ProblemException.dependencyUnavailable("Адрес сейчас сменить нельзя, повторите позже.", RETRY_AFTER_SECONDS);
        }
    }

    private void restoreInKeycloak(UUID userId, String previousEmail) {
        try {
            provider.setEmail(userId, previousEmail);
        } catch (KeycloakException e) {
            LOG.warn("Прежний адрес пользователя {} не возвращён в Keycloak: {}", userId, e.getMessage());
        }
    }

    private static ProblemException emailNotAllowed() {
        return new ProblemException(ProblemType.EMAIL_NOT_ALLOWED, "Адрес использовать нельзя.");
    }
}
