package dgm.platform.identity.service;

import dgm.kit.keycloak.KeycloakException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.identity.domain.Actor;
import dgm.platform.identity.domain.LastAdminException;
import dgm.platform.identity.domain.Roles;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.domain.UserFilter;
import dgm.platform.identity.domain.UserPage;
import dgm.platform.identity.repository.UserAccountRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Операции администратора над учётными записями (OP-80, OP-81, OP-82; US-8.1, US-8.2).
 *
 * <p>Порядок каждой операции один (ADR-025, пункт 4): в одной транзакции базы меняется запись и пишутся события и аудит (Outbox), и только
 * после её фиксации идёт вызов Keycloak Admin REST. Если Keycloak не ответил, изменение остаётся в базе, ответ администратору всё равно
 * успешный, а согласие сторон возвращает {@link IdentityReconciler}: для роли, деактивации и анонимизации образцом служит база платформы.
 * Обратный порядок (сначала Keycloak) оставил бы Keycloak изменённым при откате базы, и вернуть его было бы нечем.
 */
@Component
public final class StaffUserService {

    private static final Logger LOG = LoggerFactory.getLogger(StaffUserService.class);

    private final UserAccountRepository accounts;
    private final IdentityProvider provider;
    private final IdentityTransaction tx;
    private final Clock clock;

    public StaffUserService(UserAccountRepository accounts, IdentityProvider provider, IdentityTransaction tx, Clock clock) {
        this.accounts = accounts;
        this.provider = provider;
        this.tx = tx;
        this.clock = clock;
    }

    /** Страница учётных записей, новые выше (OP-80). */
    public UserPage list(UserFilter filter) {
        List<UserAccount> rows = accounts.list(filter);
        boolean more = rows.size() > filter.limit();
        List<UserAccount> items = more ? rows.subList(0, filter.limit()) : rows;
        Optional<UserFilter.After> next = more
                ? Optional.of(new UserFilter.After(items.get(items.size() - 1).registeredAt(), items.get(items.size() - 1).id()))
                : Optional.empty();
        return new UserPage(List.copyOf(items), filter.limit(), next);
    }

    /**
     * Назначает роль или возвращает роль покупателя (OP-80). Роль продавца вручную не назначается и не снимается (INV-36, U8); после
     * последнего администратора система не остаётся (INV-38, U7); деактивированной записи роль не меняют. Та же роль повторно: ответ без
     * изменений и без нового события.
     */
    public UserAccount changeRole(Actor actor, UUID userId, String role) {
        if (!Roles.known(role)) {
            throw new IllegalArgumentException("Неизвестная роль " + role);
        }
        UserAccount result = tx.execute(() -> {
            UserAccount current = lock(userId);
            if (!current.active()) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Роль деактивированной учётной записи не меняют.");
            }
            if (Roles.SELLER.equals(role) || Roles.SELLER.equals(current.role())) {
                throw new ProblemException(ProblemType.ROLE_MANAGED_BY_SYSTEM,
                        "Роль продавца назначается и снимается системой по решению о заявке, вручную её менять нельзя.");
            }
            if (current.role().equals(role)) {
                return current;
            }
            try {
                accounts.updateRole(userId, role);
            } catch (LastAdminException e) {
                throw lastAdmin(e);
            }
            tx.publish(IdentityEvents.USER_TOPIC, IdentityEvents.USER_AGGREGATE, userId.toString(), IdentityEvents.ROLE_ASSIGNED, 1,
                    IdentityEvents.roleAssigned(userId, role, actor.id()));
            tx.publish(IdentityEvents.AUDIT_TOPIC, IdentityEvents.AUDIT_AGGREGATE, userId.toString(), IdentityEvents.AUDIT_EVENT, 1,
                    IdentityEvents.audit(clock.instant(), actor, IdentityEvents.AUDIT_ROLE_ASSIGNED, userId,
                            List.of(IdentityEvents.change("role", current.role(), role))));
            return reread(userId);
        });
        pushRole(result);
        return result;
    }

    /** Деактивирует учётную запись и завершает сессии (OP-81). Повтор ничего не меняет и событие не пишет. */
    public UserAccount deactivate(Actor actor, UUID userId) {
        UserAccount result = tx.execute(() -> {
            UserAccount current = lock(userId);
            if (!current.active()) {
                return current;
            }
            try {
                accounts.deactivate(userId, clock.instant());
            } catch (LastAdminException e) {
                throw lastAdmin(e);
            }
            tx.publish(IdentityEvents.USER_TOPIC, IdentityEvents.USER_AGGREGATE, userId.toString(), IdentityEvents.DEACTIVATED, 1,
                    IdentityEvents.deactivated(userId, actor.id()));
            tx.publish(IdentityEvents.AUDIT_TOPIC, IdentityEvents.AUDIT_AGGREGATE, userId.toString(), IdentityEvents.AUDIT_EVENT, 1,
                    IdentityEvents.audit(clock.instant(), actor, IdentityEvents.AUDIT_DEACTIVATED, userId,
                            List.of(IdentityEvents.change("status", UserAccount.ACTIVE, UserAccount.DEACTIVATED))));
            return reread(userId);
        });
        attempt("отключение", result.id(), () -> provider.disable(result.id()));
        return result;
    }

    /**
     * Анонимизирует деактивированную учётную запись (OP-82, U11): персональные значения заменяются служебными, соль аудита удаляется, пользователь
     * удаляется в Keycloak. В аудит и события идёт только идентификатор (ADR-014). Повтор ничего не меняет.
     */
    public UserAccount anonymize(Actor actor, UUID userId) {
        UserAccount result = tx.execute(() -> {
            UserAccount current = lock(userId);
            if (current.anonymized()) {
                return current;
            }
            if (current.active()) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Анонимизировать можно только деактивированную учётную запись.");
            }
            accounts.anonymize(userId);
            tx.publish(IdentityEvents.USER_TOPIC, IdentityEvents.USER_AGGREGATE, userId.toString(), IdentityEvents.ANONYMIZED, 1,
                    IdentityEvents.anonymized(userId));
            tx.publish(IdentityEvents.AUDIT_TOPIC, IdentityEvents.AUDIT_AGGREGATE, userId.toString(), IdentityEvents.AUDIT_EVENT, 1,
                    IdentityEvents.audit(clock.instant(), actor, IdentityEvents.AUDIT_ANONYMIZED, userId,
                            List.of(IdentityEvents.change("anonymized", false, true))));
            return reread(userId);
        });
        attempt("удаление", result.id(), () -> provider.delete(result.id()));
        return result;
    }

    private UserAccount lock(UUID userId) {
        return accounts.findForUpdate(userId).orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Учётная запись не найдена."));
    }

    private UserAccount reread(UUID userId) {
        return accounts.find(userId).orElseThrow(() -> new IllegalStateException("Запись пропала внутри транзакции: " + userId));
    }

    private static ProblemException lastAdmin(LastAdminException e) {
        return new ProblemException(ProblemType.LAST_ADMIN_REQUIRED, e.getMessage());
    }

    /** Роль уже в базе; Keycloak догоняет после фиксации. Для записи без изменений вызов тоже идёт: он идемпотентен и чинит прошлый сбой. */
    private void pushRole(UserAccount account) {
        boolean requireTotp = Roles.secondFactorRequired(account.role()) && !account.has2fa();
        attempt("назначение роли", account.id(), () -> provider.applyRole(account.id(), account.role(), requireTotp));
    }

    private static void attempt(String what, UUID id, Runnable call) {
        try {
            call.run();
        } catch (KeycloakException e) {
            LOG.warn("Keycloak не выполнил {} для {}, повторит сверка: {}", what, id, e.getMessage());
        }
    }
}
