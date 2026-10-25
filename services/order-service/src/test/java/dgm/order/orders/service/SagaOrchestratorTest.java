package dgm.order.orders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.config.ConfigRegistry;
import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.order.orders.client.CatalogClient;
import dgm.order.orders.client.CatalogClient.ProductCard;
import dgm.order.orders.client.InventoryClient;
import dgm.order.orders.client.InventoryClient.OutOfStock;
import dgm.order.orders.client.InventoryClient.ReserveResult;
import dgm.order.orders.client.InventoryClient.Reserved;
import dgm.order.orders.client.InventoryClient.UnknownProduct;
import dgm.order.orders.client.PaymentClient;
import dgm.order.orders.client.PaymentClient.Opened;
import dgm.order.orders.client.PaymentClient.Refused;
import dgm.order.orders.client.PaymentClient.SessionResult;
import dgm.order.orders.domain.CancelReason;
import dgm.order.orders.domain.OrderPricing;
import dgm.order.orders.domain.OrderStatus;
import dgm.order.orders.domain.StoredOrder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Сага покупки, шаги 1 – 3 (SEQ-01, ADR-004), с подменой базы и трёх соседей. Проверяется порядок вызовов, их число, цена из каталога, сроки
 * (сессия короче резерва, INV-07) и компенсация на каждом из трёх вызовов: заказ получает нужную отмену и событие, покупатель нужный ответ.
 * Соседи вызываются вне транзакций (правило 9): заглушки падают, если вызваны внутри.
 */
class SagaOrchestratorTest {

    private static final Instant T0 = Instant.parse("2026-10-18T12:00:00.123Z");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final String EMAIL = "buyer@mail.example";
    private static final String URL = "https://localhost:8443/payment/pay/pay_1";
    private static final ProductCard CARD = new ProductCard(PRODUCT, SELLER, "Лунная тропа: ключ Steam", "published", 149_900, "RUB", "key_pool");

    private FakeOrderStore store;
    private ManualClock clock;
    private SimpleMeterRegistry meters;
    private ConfigRegistry registry;
    private OrderTransitions transitions;
    private Catalog catalog;
    private Inventory inventory;
    private Payments payments;
    private SagaOrchestrator saga;

    /** Заглушка каталога. */
    private final class Catalog implements CatalogClient {
        Optional<ProductCard> card = Optional.of(CARD);
        RuntimeException failure;
        final List<UUID> asked = new ArrayList<>();

        @Override
        public Optional<ProductCard> card(UUID productId) {
            outsideTransaction();
            asked.add(productId);
            if (failure != null) {
                throw failure;
            }
            return card;
        }
    }

    /** Заглушка сервиса остатков. */
    private final class Inventory implements InventoryClient {
        ReserveResult result;
        RuntimeException failure;
        Runnable during = () -> {
        };
        final List<UUID> orders = new ArrayList<>();
        final List<Integer> quantities = new ArrayList<>();

        @Override
        public ReserveResult reserve(UUID orderId, UUID productId, int quantity) {
            outsideTransaction();
            orders.add(orderId);
            quantities.add(quantity);
            during.run();
            if (failure != null) {
                throw failure;
            }
            return result != null ? result : new Reserved(UUID.randomUUID(), clock.instant().plus(Duration.ofMinutes(15)));
        }

        @Override
        public ConfirmResult confirm(UUID orderId, UUID productId, int quantity) {
            throw new UnsupportedOperationException("Подтверждение резерва (шаг 5) проверяет SagaEventsTest");
        }
    }

    /** Заглушка сервиса платежей. */
    private final class Payments implements PaymentClient {
        SessionResult result;
        RuntimeException failure;
        final List<UUID> orders = new ArrayList<>();
        final List<Long> amounts = new ArrayList<>();
        final List<Instant> ends = new ArrayList<>();

        @Override
        public SessionResult open(UUID orderId, long amount, String currency, Instant sessionExpiresAt) {
            outsideTransaction();
            assertEquals("RUB", currency);
            orders.add(orderId);
            amounts.add(amount);
            ends.add(sessionExpiresAt);
            if (failure != null) {
                throw failure;
            }
            return result != null ? result : new Opened(URL, sessionExpiresAt);
        }
    }

    private void outsideTransaction() {
        if (store.inTransaction()) {
            throw new IllegalStateException("Сосед вызван внутри транзакции (правило 9)");
        }
    }

    @BeforeEach
    void setUp() {
        store = new FakeOrderStore();
        clock = new ManualClock(T0);
        meters = new SimpleMeterRegistry();
        registry = new ConfigRegistry(List.of(), meters);
        OrderSettings settings = OrderSettings.live(registry, new OrderSettings(3, Duration.ofMinutes(15), Duration.ofMinutes(12), 200));
        transitions = new OrderTransitions(store, store, settings, meters);
        catalog = new Catalog();
        inventory = new Inventory();
        payments = new Payments();
        saga = new SagaOrchestrator(catalog, inventory, payments, transitions, settings, clock, meters);
    }

    private static DependencyUnavailableException down(String peer) {
        return new DependencyUnavailableException(peer, DependencyUnavailableException.TIMEOUT, 0, null);
    }

    private ProblemException create(int quantity) {
        return assertThrows(ProblemException.class, () -> saga.create(BUYER, EMAIL, PRODUCT, quantity));
    }

    private StoredOrder only() {
        assertEquals(1, store.all().size());
        return store.all().get(0);
    }

    private double count(String name, String tag, String value) {
        var counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    // ------------------------------------------------------------------------------------------------ TC-809

    private static UUID buyer(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-4000-8000-0000000001%02d", n));
    }

    private void priced(long unitPrice) {
        catalog.card = Optional.of(new ProductCard(PRODUCT, SELLER, "Лунная тропа: ключ Steam", "published", unitPrice, "RUB", "key_pool"));
    }

    @Tag("TC-809")
    @Test
    void theDefaultRateOfTwoPercentGivesTwentyRoublesOnAThousand() {
        priced(100_000);
        assertEquals(2_000L, saga.create(buyer(1), EMAIL, PRODUCT, 1).commission(), "1000 ₽ при 2 % это 20 ₽ (US-8.4, сценарий 1)");
    }

    @Tag("TC-809")
    @Test
    void theCommissionOfTheRequirementsScenarioIsRoundedHalfUpToTheKopeck() {
        priced(1_025);
        assertEquals(21L, saga.create(buyer(1), EMAIL, PRODUCT, 1).commission(), "10,25 ₽ при 2 % это 0,205 ₽, округляется до 0,21 ₽");
        priced(1_250);
        assertEquals(25L, saga.create(buyer(2), EMAIL, PRODUCT, 1).commission(), "12,50 ₽ при 2 % это ровно 0,25 ₽");
        priced(1_024);
        assertEquals(20L, saga.create(buyer(3), EMAIL, PRODUCT, 1).commission(), "10,24 ₽ при 2 % это 0,2048 ₽, округляется вниз");
    }

    @Tag("TC-809")
    @Test
    void theRateIsFixedInTheOrderAtTheMomentOfCreationAndALaterChangeTouchesOnlyNewOrders() {
        StoredOrder before = saga.create(buyer(1), EMAIL, PRODUCT, 1);
        assertEquals(2_998L, before.commission(), "2 % от 1499 ₽");

        registry.apply("commission.default-rate-bp", 300, 2);
        StoredOrder after = saga.create(buyer(2), EMAIL, PRODUCT, 1);

        assertEquals(4_497L, after.commission(), "3 % от 1499 ₽");
        StoredOrder stored = store.all().stream().filter(o -> o.id().equals(before.id())).findFirst().orElseThrow();
        assertEquals(2_998L, stored.commission(), "заказ, созданный раньше, ставку не меняет");
    }

    @Tag("TC-809")
    @Test
    void aRateOutsideTheBoundsOfTheServiceIsNotAppliedAndTheOrdersKeepTheLastGoodRate() {
        registry.apply("commission.default-rate-bp", 10_001, 2);
        assertEquals(2_998L, saga.create(buyer(1), EMAIL, PRODUCT, 1).commission());
    }

    // ------------------------------------------------------------------------------------------------ TC-511

    @Tag("TC-511")
    @Test
    void theHappyPathLeavesTheOrderAwaitingPaymentWithThePriceOfTheCatalog() {
        StoredOrder order = saga.create(BUYER, EMAIL, PRODUCT, 2);

        assertEquals(OrderStatus.AWAITING_PAYMENT, order.status());
        assertEquals(1001L, order.number());
        assertEquals(BUYER, order.buyerId());
        assertEquals(SELLER, order.sellerId());
        assertEquals(PRODUCT, order.productId());
        assertEquals("Лунная тропа: ключ Steam", order.productTitle());
        assertEquals(2, order.quantity());
        assertEquals(149_900L, order.unitPrice(), "цена из каталога, а не из запроса");
        assertEquals(299_800L, order.amount());
        assertEquals(OrderPricing.commission(299_800, 200), order.commission());
        assertEquals(5_996L, order.commission());
        assertEquals("RUB", order.currency());
        assertEquals(EMAIL, order.deliveryAddress());
        assertEquals(T0.plus(Duration.ofMinutes(15)), order.reserveUntil());
        assertEquals(T0.plus(Duration.ofMinutes(12)), order.sessionUntil());
        assertEquals(URL, order.paymentSessionUrl());
        assertEquals(T0, order.createdAt());
        assertNull(order.cancelReason());
    }

    @Tag("TC-511")
    @Test
    void everyNeighbourIsCalledOnceInTheOrderOfTheSequenceWithTheDataOfTheOrder() {
        StoredOrder order = saga.create(BUYER, EMAIL, PRODUCT, 2);

        assertEquals(List.of(PRODUCT), catalog.asked);
        assertEquals(List.of(order.id()), inventory.orders);
        assertEquals(List.of(2), inventory.quantities);
        assertEquals(List.of(order.id()), payments.orders);
        assertEquals(List.of(299_800L), payments.amounts);
        assertEquals(List.of(T0.plus(Duration.ofMinutes(12))), payments.ends);
        assertEquals(2, store.transactions(), "две короткие транзакции: вставка заказа и переход в «ожидает оплаты»");
    }

    @Tag("TC-511")
    @Test
    void onlyOrderCreatedIsPublishedAndItCarriesTheDeadlines() {
        StoredOrder order = saga.create(BUYER, EMAIL, PRODUCT, 1);

        assertEquals(1, store.events().size());
        FakeOrderStore.Published event = store.events().get(0);
        assertEquals("order.created", event.eventType());
        assertEquals(order.id().toString(), event.aggregateId());
        assertEquals(0, store.events("order.cancelled").size());
        assertEquals(1.0, count("orders_created_total", "stage", "awaiting_payment"));
    }

    @Tag("TC-511")
    @Test
    void theOrderIdIsAVersion7UuidBuiltFromTheClock() {
        StoredOrder order = saga.create(BUYER, EMAIL, PRODUCT, 1);

        assertEquals(T0.toEpochMilli(), Uuids.timestampMillis(order.id()));
        assertEquals(7, order.id().version());
    }

    @Tag("TC-511")
    @Test
    void theSessionNeverOutlivesTheReserveEvenWhenTheReserveWasShort() {
        inventory.result = new Reserved(UUID.randomUUID(), T0.plusSeconds(60));

        StoredOrder order = saga.create(BUYER, EMAIL, PRODUCT, 1);

        assertEquals(T0.plusSeconds(60), order.reserveUntil());
        assertEquals(T0.plusSeconds(59), order.sessionUntil(), "сессия кончается за секунду до конца резерва (INV-07)");
        assertEquals(List.of(T0.plusSeconds(59)), payments.ends);
    }

    @Tag("TC-511")
    @Test
    void anEarlierEndOfTheSessionGivenByThePaymentServiceIsKept() {
        payments.result = new Opened(URL, T0.plusSeconds(600));

        StoredOrder order = saga.create(BUYER, EMAIL, PRODUCT, 1);

        assertEquals(T0.plusSeconds(600), order.sessionUntil());
    }

    @Tag("TC-511")
    @Test
    void aReserveThatIsAlmostOverLeavesNoRoomForASessionAndTheOrderIsCancelled() {
        inventory.result = new Reserved(UUID.randomUUID(), T0.plusMillis(500));

        ProblemException e = create(1);

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals(0, payments.orders.size(), "платёжная сессия не открывалась");
        assertEquals("system_error", only().cancelReason());
        assertEquals(1, store.events("order.cancelled").size(), "резерв снимается событием");
    }

    @Tag("TC-511")
    @Test
    void anOrderCancelledWhileTheSagaWasWorkingIsNotRevivedAndTheBuyerIsAskedToRetry() {
        inventory.during = () -> {
            StoredOrder created = store.all().get(0);
            transitions.cancel(created.id(), CancelReason.SYSTEM_ERROR);
        };

        ProblemException e = create(1);

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals("5", e.headers().get("Retry-After"));
        assertEquals(OrderStatus.CANCELLED, only().status());
        assertEquals(0, store.events("order.created").size());
    }

    // ------------------------------------------------------------------------------------------------ TC-512

    @Tag("TC-512")
    @Test
    void whenTheLimitIsReachedNothingIsReservedAndNoSessionIsOpened() {
        saga.create(BUYER, EMAIL, PRODUCT, 1);
        saga.create(BUYER, EMAIL, PRODUCT, 1);
        saga.create(BUYER, EMAIL, PRODUCT, 1);
        int reserves = inventory.orders.size();
        int sessions = payments.orders.size();

        ProblemException e = create(1);

        assertEquals(ProblemType.UNPAID_ORDERS_LIMIT, e.type());
        assertEquals(3, e.extensions().get("limit"));
        assertEquals(reserves, inventory.orders.size(), "резерв не берётся");
        assertEquals(sessions, payments.orders.size(), "сессия не открывается");
        assertEquals(3, store.all().size(), "заказ не создаётся");
        assertEquals(3, store.events("order.created").size());
        assertEquals(0, store.events("order.cancelled").size());
    }

    // ------------------------------------------------------------------------------------------------ TC-513

    @Tag("TC-513")
    @Test
    void anUnavailableCatalogGives503BeforeAnythingIsCreated() {
        catalog.failure = down("catalog-service");

        ProblemException e = create(1);

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals("5", e.headers().get("Retry-After"));
        assertEquals(0, store.all().size());
        assertEquals(0, inventory.orders.size());
        assertEquals(0, payments.orders.size());
        assertEquals(0, store.events().size());
        assertEquals(1.0, count("orders_rejected_total", "reason", "catalog-unavailable"));
    }

    @Tag("TC-513")
    @Test
    void aProductThatCannotBeSoldIsRefusedBeforeAnythingIsCreated() {
        List<Optional<ProductCard>> cards = List.of(
                Optional.empty(),
                Optional.of(new ProductCard(PRODUCT, SELLER, "Игра", "blocked", 100_00, "RUB", "key_pool")),
                Optional.of(new ProductCard(PRODUCT, SELLER, "Игра", "draft", 100_00, "RUB", "key_pool")),
                Optional.of(new ProductCard(PRODUCT, SELLER, "Игра", "published", 100_00, "RUB", "seller_api")),
                Optional.of(new ProductCard(PRODUCT, SELLER, "Игра", "published", 100_00, "USD", "key_pool")));
        for (Optional<ProductCard> card : cards) {
            catalog.card = card;

            ProblemException e = create(1);

            assertEquals(ProblemType.PRODUCT_UNAVAILABLE, e.type(), card.toString());
        }
        assertEquals(0, store.all().size());
        assertEquals(0, inventory.orders.size());
        assertEquals(0, payments.orders.size());
    }

    @Tag("TC-513")
    @Test
    void aTotalOutsideTheContractMakesTheProductUnavailableForOrdering() {
        catalog.card = Optional.of(new ProductCard(PRODUCT, SELLER, "Игра", "published", OrderPricing.MAX_AMOUNT, "RUB", "key_pool"));

        ProblemException e = create(2);

        assertEquals(ProblemType.PRODUCT_UNAVAILABLE, e.type());
        assertEquals(0, store.all().size());
    }

    @Tag("TC-513")
    @Test
    void notEnoughKeysCancelsTheOrderAsOutOfStockAndTellsHowManyAreLeft() {
        inventory.result = new OutOfStock(1);

        ProblemException e = create(2);

        assertEquals(ProblemType.INSUFFICIENT_STOCK, e.type());
        assertEquals(1, e.extensions().get("available"));
        StoredOrder order = only();
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals("out_of_stock", order.cancelReason());
        assertEquals(0, payments.orders.size(), "платёжная сессия не открывалась");
        assertEquals(1, store.events("order.cancelled").size());
        assertEquals(0, store.events("order.created").size());
        assertEquals(1.0, meters.find("orders_cancelled_total").tag("transition", "T3").counter().count());
    }

    @Tag("TC-513")
    @Test
    void aSilentInventoryServiceCancelsTheOrderAsASystemErrorAndTheEventReleasesAReserveThatMayHaveBeenMade() {
        inventory.failure = down("inventory-service");

        ProblemException e = create(1);

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals("5", e.headers().get("Retry-After"));
        StoredOrder order = only();
        assertEquals("system_error", order.cancelReason());
        assertEquals(1, store.events("order.cancelled").size());
        assertEquals("system_error", store.events("order.cancelled").get(0).data().get("cancelReason"));
        assertEquals(0, payments.orders.size());
        assertEquals(1.0, meters.find("orders_cancelled_total").tag("transition", "T12").counter().count());
    }

    @Tag("TC-513")
    @Test
    void anInventoryServiceThatDoesNotKnowTheProductCancelsTheOrderAndTheProductIsUnavailable() {
        inventory.result = new UnknownProduct();

        ProblemException e = create(1);

        assertEquals(ProblemType.PRODUCT_UNAVAILABLE, e.type());
        assertEquals("system_error", only().cancelReason());
        assertEquals(0, payments.orders.size());
    }

    @Tag("TC-513")
    @Test
    void aPaymentSessionThatCannotBeOpenedCancelsTheOrderAndReleasesTheReserveByEvent() {
        payments.failure = down("payment-service");

        ProblemException e = create(2);

        assertEquals(ProblemType.PAYMENT_SESSION_UNAVAILABLE, e.type());
        assertEquals(503, e.type().status());
        assertEquals("5", e.headers().get("Retry-After"));
        StoredOrder order = only();
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals("payment_session_failed", order.cancelReason());
        assertEquals(1, inventory.orders.size(), "резерв был взят");
        List<FakeOrderStore.Published> cancelled = store.events("order.cancelled");
        assertEquals(1, cancelled.size(), "по этому событию сервис остатков снимет резерв");
        assertEquals("payment_session_failed", cancelled.get(0).data().get("cancelReason"));
        assertEquals(2, cancelled.get(0).data().get("quantity"));
        assertEquals(0, store.events("order.created").size());
        assertEquals(1.0, meters.find("orders_cancelled_total").tag("transition", "T12").counter().count());
    }

    @Tag("TC-513")
    @Test
    void aRefusalOfThePaymentServiceIsTreatedLikeAnUnavailableSession() {
        payments.result = new Refused(409);

        ProblemException e = create(1);

        assertEquals(ProblemType.PAYMENT_SESSION_UNAVAILABLE, e.type());
        assertEquals("payment_session_failed", only().cancelReason());
        assertEquals(1, store.events("order.cancelled").size());
    }

    @Tag("TC-513")
    @Test
    void aFailedAttemptFreesTheSlotOfTheBuyer() {
        payments.failure = down("payment-service");
        for (int i = 0; i < 5; i++) {
            assertEquals(ProblemType.PAYMENT_SESSION_UNAVAILABLE, create(1).type());
        }
        payments.failure = null;

        StoredOrder order = saga.create(BUYER, EMAIL, PRODUCT, 1);

        assertEquals(OrderStatus.AWAITING_PAYMENT, order.status());
        assertEquals(6, store.all().size());
        assertNotNull(order.paymentSessionUrl());
        assertTrue(store.all().stream().filter(o -> OrderStatus.CANCELLED.equals(o.status())).count() == 5);
    }
}
