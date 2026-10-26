package dgm.platform.identity.sellerrole;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.HandleResult;
import dgm.kit.time.ManualClock;
import dgm.platform.identity.service.AccountService;
import dgm.platform.identity.service.IdentityReconciler;
import dgm.platform.identity.testing.FakeIdentityProvider;
import dgm.platform.identity.testing.FakeTransaction;
import dgm.platform.identity.testing.InMemoryAccounts;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Обработчик и сервис роли продавца поверх учётных записей и Keycloak в памяти; событие доставляется так же, как потребителем: в транзакции. */
final class SellerRoleTestKit {

    static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    static final UUID USER = id(1);
    static final UUID SELLER = id(101);

    final InMemoryAccounts accounts = new InMemoryAccounts();
    final FakeTransaction tx = new FakeTransaction();
    final FakeIdentityProvider keycloak = new FakeIdentityProvider(tx);
    final SellerRoleHandler handler = new SellerRoleHandler(new SellerRoleService(accounts, keycloak, tx, new ManualClock(NOW)));
    final IdentityReconciler reconciler = new IdentityReconciler(accounts, keycloak,
            new AccountService(accounts, keycloak, tx, new ManualClock(NOW)), new StaticListableBeanFactory().getBeanProvider(dgm.kit.outbox.OutboxRelay.class),
            new ManualClock(NOW));

    static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-4000-8000-%012d", n));
    }

    /** Учётная запись и такой же пользователь Keycloak с той же ролью. */
    void user(UUID id, String role) {
        accounts.add(id, "user-" + id.toString().substring(31) + "@mail.example", role, NOW);
        keycloak.add(FakeIdentityProvider.user(id, "user-" + id.toString().substring(31) + "@mail.example", null, null, true), role);
    }

    static Map<String, Object> data(UUID user, UUID seller) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sellerId", seller.toString());
        data.put("userId", user.toString());
        data.put("moderatorId", id(900).toString());
        data.put("sellerType", "self_employed");
        return data;
    }

    static EventEnvelope event(Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), "catalog-service", "seller.approved", NOW, SELLER.toString(), 1, "SellerProfile",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90222-01", UUID.randomUUID().toString(), data);
    }

    /** Доставка события потребителем: обработчик вызывается внутри транзакции. */
    HandleResult deliver(UUID user, UUID seller) {
        return deliver(event(data(user, seller)));
    }

    HandleResult deliver(EventEnvelope event) {
        return tx.execute(() -> handler.handle(event));
    }
}
