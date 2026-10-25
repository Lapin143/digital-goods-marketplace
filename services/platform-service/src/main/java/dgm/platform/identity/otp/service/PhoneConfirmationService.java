package dgm.platform.identity.otp.service;

import dgm.kit.keycloak.KeycloakException;
import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.identity.domain.PhoneTakenException;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.otp.crypto.AuditHasher;
import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.repository.UserAccountRepository;
import dgm.platform.identity.service.IdentityEvents;
import dgm.platform.identity.service.IdentityProvider;
import dgm.platform.identity.service.IdentityTransaction;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Подтверждение телефона покупателя (OP-06, US-1.6, ADR-010): запрос кода и проверка кода.
 *
 * <p>Запрос: учётная запись должна быть активной и без подтверждённого телефона; запрос учитывается в лимитах до проверки номера на
 * уникальность, поэтому перебор чужих номеров упирается в те же три запроса за 10 минут. Номер уже у другой записи: SMS не уходит, ответ
 * 409 {@code phone-not-allowed} без причины. До проверки кода номера в базе нет: он лежит в записи кода в Redis вместе с хешем кода.
 *
 * <p>Проверка: код гасится в Redis неделимо, затем в одной транзакции пишутся номер и признак, событие {@code user.phone-confirmed} и
 * запись аудита (персональные поля только хешами, ADR-014). После фиксации атрибут {@code phone_verified} передаётся в Keycloak (2 секунды);
 * если Keycloak не ответил, признак {@code phone_synced_to_idp} остаётся ложным и передачу повторяет сверка. Код при этом уже погашен, а
 * номер подтверждён: ответ покупателю успешный. Проигравший гонку за номер получает 409, погашенный им код не восстанавливается.
 */
@Component
public final class PhoneConfirmationService {

    private static final Logger LOG = LoggerFactory.getLogger(PhoneConfirmationService.class);
    private static final String UNKNOWN_IP = "unknown";

    /**
     * Начатое подтверждение.
     *
     * @param confirmationId идентификатор для проверки кода
     * @param phone          номер, на который ушёл код
     * @param expiresAt      до какого момента действует код
     * @param attempts       попыток ввода
     * @param resendAt       когда можно запросить новый код; пусто, если можно сразу
     */
    public record Started(UUID confirmationId, PhoneNumber phone, Instant expiresAt, int attempts, Optional<Instant> resendAt) {
    }

    private final UserAccountRepository accounts;
    private final IdentityProvider provider;
    private final IdentityTransaction tx;
    private final OtpService otp;
    private final AuditHasher hasher;
    private final Clock clock;

    public PhoneConfirmationService(UserAccountRepository accounts, IdentityProvider provider, IdentityTransaction tx, OtpService otp,
                                    AuditHasher hasher, Clock clock) {
        this.accounts = accounts;
        this.provider = provider;
        this.tx = tx;
        this.otp = otp;
        this.hasher = hasher;
        this.clock = clock;
    }

    /**
     * Запрашивает код подтверждения и отправляет SMS.
     *
     * @throws ProblemException 409 {@code state-conflict} (телефон уже подтверждён, запись не активна) или {@code phone-not-allowed};
     *                          429 {@code otp-request-limited}; 503, если Redis или провайдер SMS недоступны
     */
    public Started start(UUID userId, PhoneNumber phone, Optional<String> ip) {
        UserAccount account = account(userId);
        if (!account.active()) {
            throw new ProblemException(ProblemType.STATE_CONFLICT, "Учётная запись деактивирована.");
        }
        if (account.phoneConfirmed()) {
            throw new ProblemException(ProblemType.STATE_CONFLICT, "Телефон уже подтверждён.");
        }
        OtpStore.Admission admission = otp.admitConfirmation(userId, phone, ip);
        Optional<UserAccount> holder = accounts.findByPhone(phone.value());
        if (holder.isPresent() && !holder.get().id().equals(userId)) {
            throw phoneNotAllowed();
        }
        OtpService.Issued issued = otp.issueConfirmation(userId, phone, admission);
        return new Started(issued.confirmationId(), phone, issued.expiresAt(), issued.attempts(), issued.resendAt());
    }

    /**
     * Проверяет код и подтверждает телефон.
     *
     * @param role роль покупателя для записи аудита
     * @return подтверждённый номер
     * @throws ProblemException 422 {@code otp-invalid} / {@code otp-expired}; 429 {@code otp-locked}; 404, если запрос подтверждения
     *                          не найден; 409, если номер занят другой записью
     */
    public PhoneNumber verify(UUID userId, String role, UUID confirmationId, String code, Optional<String> ip) {
        PhoneNumber phone = otp.verifyConfirmation(userId, confirmationId, code);
        Instant now = clock.instant();
        tx.execute(() -> {
            UserAccount account = accounts.findForUpdate(userId)
                    .orElseThrow(() -> new IllegalStateException("Учётная запись пропала: " + userId));
            if (!account.active() || account.phoneConfirmed()) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Телефон подтвердить нельзя: запись не активна или он уже подтверждён.");
            }
            Optional<UserAccount> holder = accounts.findByPhone(phone.value());
            if (holder.isPresent()) {
                throw phoneNotAllowed();
            }
            boolean saved;
            try {
                saved = accounts.confirmPhone(userId, phone.value());
            } catch (PhoneTakenException e) {
                throw phoneNotAllowed();
            }
            if (!saved) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Телефон подтвердить нельзя: запись не активна или он уже подтверждён.");
            }
            byte[] salt = accounts.auditSalt(userId).orElseThrow(() -> new IllegalStateException("У записи нет соли аудита: " + userId));
            tx.publish(IdentityEvents.USER_TOPIC, IdentityEvents.USER_AGGREGATE, userId.toString(), IdentityEvents.PHONE_CONFIRMED, 1,
                    IdentityEvents.phoneConfirmed(userId));
            tx.publish(IdentityEvents.AUDIT_TOPIC, IdentityEvents.AUDIT_AGGREGATE, userId.toString(), IdentityEvents.AUDIT_EVENT, 1,
                    IdentityEvents.userAudit(now, userId, role, IdentityEvents.AUDIT_PHONE_CONFIRMED, userId,
                            List.of(AuditRecords.personalChange("phone", null, hasher.hash(salt, "phone", phone.value())),
                                    IdentityEvents.change("phone_confirmed", false, true)),
                            hasher.hash(salt, "ip", ip.orElse(UNKNOWN_IP))));
            return Boolean.TRUE;
        });
        pushToKeycloak(userId);
        return phone;
    }

    /** Атрибут для токена передаётся после фиксации; сбой не отменяет подтверждение, повторит сверка. */
    private void pushToKeycloak(UUID userId) {
        try {
            provider.markPhoneVerified(userId);
            accounts.markPhoneSynced(userId);
        } catch (KeycloakException e) {
            LOG.warn("Keycloak не принял признак подтверждённого телефона для {}, повторит сверка: {}", userId, e.getMessage());
        }
    }

    private UserAccount account(UUID userId) {
        return accounts.find(userId).orElseThrow(() -> new IllegalStateException("Учётная запись пользователя токена не создана: " + userId));
    }

    private static ProblemException phoneNotAllowed() {
        return new ProblemException(ProblemType.PHONE_NOT_ALLOWED, "Этот номер нельзя использовать.");
    }
}
