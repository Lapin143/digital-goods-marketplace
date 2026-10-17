package dgm.platform.identity.sellerrole;

import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.events.RetryableEventException;
import dgm.kit.keycloak.KeycloakException;
import dgm.platform.identity.domain.Actor;
import dgm.platform.identity.domain.Roles;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.repository.UserAccountRepository;
import dgm.platform.identity.service.IdentityEvents;
import dgm.platform.identity.service.IdentityProvider;
import dgm.platform.identity.service.IdentityTransaction;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Назначение роли «Продавец» по событию {@code seller.approved} (INV-36, INV-37, Р-18).
 *
 * <p>Метод {@link #assign} вызывается обработчиком внутри транзакции потребителя. Порядок тот же, что у операций администратора
 * (ADR-025, пункт 4): в транзакции базы меняется запись (роль и {@code seller_id}) и пишутся события {@code user.role-assigned} и
 * {@code audit.recorded} в Outbox, и только после её фиксации идёт обращение к Keycloak: атрибут {@code seller_id} для токена, роль
 * и обязательное действие «настроить TOTP». Так обращение к Keycloak не удерживает соединение базы (правило 2 среза), а недоступный
 * Keycloak не отправляет зафиксированное решение в повторы и DLQ: роль в базе уже есть, и {@code IdentityReconciler} раз в минуту
 * доводит Keycloak до неё. Образцом служит база платформы, поэтому проход сверки закрывает и сбой после фиксации, и падение
 * экземпляра между фиксацией и вызовом.
 *
 * <p>Повторная доставка безопасна: роль и профиль уже записаны, событие и аудит второй раз не пишутся, а обращение к Keycloak
 * повторяется, потому что идемпотентно и чинит прошлый сбой (так же работает повторная обработка из DLQ).
 */
@Component
public final class SellerRoleService {

    /** Причина игнорирования в метрике {@code events_ignored_total}: запись деактивирована или обезличена. */
    static final String ACCOUNT_INACTIVE = "account-inactive";
    /** Причина игнорирования: роль не покупатель и не продавец, понижать сотрудника заявкой продавца нельзя. */
    static final String ROLE_NOT_BUYER = "role-not-buyer";
    /** Причина игнорирования: решение уже применено, повторная доставка. */
    static final String ALREADY_SELLER = "already-seller";

    private static final Logger LOG = LoggerFactory.getLogger(SellerRoleService.class);

    private final UserAccountRepository accounts;
    private final IdentityProvider provider;
    private final IdentityTransaction tx;
    private final Clock clock;

    public SellerRoleService(UserAccountRepository accounts, IdentityProvider provider, IdentityTransaction tx, Clock clock) {
        this.accounts = accounts;
        this.provider = provider;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @throws RetryableEventException учётной записи в платформе ещё нет: её создаёт первый вызов пользователя к платформе или сверка
     *                                 (раз в минуту), повторы потребителя дают этому до 31 секунды
     * @throws FatalEventException     у учётной записи уже другой профиль продавца: так не бывает при одном профиле на пользователя, нужен
     *                                 разбор человеком, поэтому сразу в DLQ
     */
    public HandleResult assign(SellerApproval approval) {
        UUID userId = approval.userId();
        UUID sellerId = approval.sellerId();
        UserAccount account = accounts.findForUpdate(userId).orElseThrow(() -> new RetryableEventException(
                "Учётной записи " + userId + " в платформе ещё нет, повтор после создания записи"));
        if (!account.active() || account.anonymized()) {
            LOG.warn("Роль продавца не назначена: запись {} деактивирована, профиль {}", userId, sellerId);
            return HandleResult.ignored(ACCOUNT_INACTIVE);
        }
        if (!Roles.BUYER.equals(account.role()) && !Roles.SELLER.equals(account.role())) {
            LOG.warn("Роль продавца не назначена: у записи {} роль {}, профиль {}", userId, account.role(), sellerId);
            return HandleResult.ignored(ROLE_NOT_BUYER);
        }
        if (Roles.SELLER.equals(account.role())) {
            return alreadySeller(account, sellerId);
        }
        accounts.assignSeller(userId, sellerId);
        tx.publish(IdentityEvents.USER_TOPIC, IdentityEvents.USER_AGGREGATE, userId.toString(), IdentityEvents.ROLE_ASSIGNED, 1,
                IdentityEvents.sellerRoleAssigned(userId));
        publishAudit(userId, List.of(IdentityEvents.change("role", Roles.BUYER, Roles.SELLER),
                IdentityEvents.change("sellerId", null, sellerId.toString())));
        pushAfterCommit(account, sellerId);
        return HandleResult.done();
    }

    /**
     * Роль уже продавца: повторная доставка того же решения. Если профиля в записи нет (продавца завели в консоли Keycloak, сверка
     * подхватила его раньше решения), профиль дописывается, событие роли не пишется: роль не менялась.
     */
    private HandleResult alreadySeller(UserAccount account, UUID sellerId) {
        UUID userId = account.id();
        if (account.sellerId().isPresent() && !account.sellerId().get().equals(sellerId)) {
            throw new FatalEventException("У учётной записи " + userId + " уже другой профиль продавца");
        }
        boolean written = account.sellerId().isEmpty();
        if (written) {
            accounts.assignSeller(userId, sellerId);
            publishAudit(userId, List.of(IdentityEvents.change("sellerId", null, sellerId.toString())));
        }
        pushAfterCommit(account, sellerId);
        return written ? HandleResult.done() : HandleResult.ignored(ALREADY_SELLER);
    }

    private void publishAudit(UUID userId, List<Map<String, Object>> changes) {
        tx.publish(IdentityEvents.AUDIT_TOPIC, IdentityEvents.AUDIT_AGGREGATE, userId.toString(), IdentityEvents.AUDIT_EVENT, 1,
                IdentityEvents.audit(clock.instant(), Actor.SYSTEM, IdentityEvents.AUDIT_ROLE_ASSIGNED, userId, changes));
    }

    /**
     * Атрибут {@code seller_id} выставляется раньше роли: токен, выпущенный между двумя вызовами, лучше с профилем и без роли, чем с ролью
     * и без профиля (кабинет без {@code seller_id} не откроет свои товары). Второй фактор обязателен, пока в Keycloak нет устройства TOTP.
     */
    private void pushAfterCommit(UserAccount account, UUID sellerId) {
        UUID userId = account.id();
        boolean requireTotp = !account.has2fa();
        tx.afterCommit(() -> {
            try {
                provider.setSellerId(userId, sellerId);
                provider.applyRole(userId, Roles.SELLER, requireTotp);
            } catch (KeycloakException e) {
                LOG.warn("Keycloak не принял роль продавца для {}, повторит сверка: {}", userId, e.getMessage());
            }
        });
    }
}
