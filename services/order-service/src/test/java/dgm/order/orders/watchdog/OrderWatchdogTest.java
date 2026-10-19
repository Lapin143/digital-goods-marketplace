package dgm.order.orders.watchdog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.order.orders.client.CatalogClient;
import dgm.order.orders.client.InventoryClient;
import dgm.order.orders.client.PaymentClient;
import dgm.order.orders.domain.CancelReason;
import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderFilter;
import dgm.order.orders.domain.OrderSession;
import dgm.order.orders.domain.OrderStatus;
import dgm.order.orders.domain.StoredOrder;
import dgm.order.orders.repository.OrderRepository;
import dgm.order.orders.service.FakeOrderStore;
import dgm.order.orders.service.OrderSettings;
import dgm.order.orders.service.OrderTransitions;
import dgm.order.orders.service.SagaOrchestrator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Сторож заказов (ADR-004, SM-01/T6 и T12): «создан» старше 60 секунд отменяется как системная ошибка с событием для остатков, «ожидает оплаты» с
 * резервом, истёкшим больше 5 минут назад, отменяется по сроку. Сторож ходит через оркестратор и тот же код переходов, что и события, поэтому
 * проигравший гонку ничего не меняет.
 */
class OrderWatchdogTest {

    private static final Instant T0 = Instant.parse("2026-10-18T12:00:00.123Z");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");

    private FakeOrderStore store;
    private ManualClock clock;
    private SimpleMeterRegistry meters;
    private OrderTransitions transitions;
    private OrderSettings settings;
    private Repo repo;
    private OrderWatchdog watchdog;

    /** Выборки сторожа по правилам SQL из {@code JdbcOrderRepository}: статус, строгое «раньше границы», самые старые первыми, лимит. */
    private class Repo implements OrderRepository {
        RuntimeException failure;

        @Override
        public List<UUID> findCreatedBefore(Instant border, int limit) {
            check();
            return store.all().stream().filter(o -> OrderStatus.CREATED.equals(o.status()) && o.createdAt().isBefore(border))
                    .sorted(Comparator.comparing(StoredOrder::createdAt).thenComparing(StoredOrder::id)).limit(limit).map(StoredOrder::id).toList();
        }

        @Override
        public List<UUID> findAwaitingReserveEndedBefore(Instant border, int limit) {
            check();
            return store.all().stream().filter(o -> OrderStatus.AWAITING_PAYMENT.equals(o.status()) && o.reserveUntil().isBefore(border))
                    .sorted(Comparator.comparing(StoredOrder::reserveUntil).thenComparing(StoredOrder::id)).limit(limit).map(StoredOrder::id).toList();
        }

        private void check() {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public List<Order> findByBuyer(OrderFilter filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Order> findOwned(UUID orderId, UUID buyerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<OrderSession> findSession(UUID orderId, UUID buyerId) {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeEach
    void setUp() {
        store = new FakeOrderStore();
        clock = new ManualClock(T0);
        meters = new SimpleMeterRegistry();
        settings = new OrderSettings(3, Duration.ofMinutes(15), Duration.ofMinutes(12), 200);
        transitions = new OrderTransitions(store, store, settings, meters);
        repo = new Repo();
        watchdog = newWatchdog(repo, WatchdogSettings.defaults());
    }

    /** Сторож на своих настройках: оркестратор настоящий, соседей он не вызывает. */
    private OrderWatchdog newWatchdog(OrderRepository orders, WatchdogSettings watchdogSettings) {
        CatalogClient catalog = productId -> Optional.empty();
        InventoryClient inventory = new InventoryClient() {
            @Override
            public ReserveResult reserve(UUID orderId, UUID productId, int quantity) {
                throw new UnsupportedOperationException("Сторож соседей не вызывает");
            }

            @Override
            public ConfirmResult confirm(UUID orderId, UUID productId, int quantity) {
                throw new UnsupportedOperationException("Сторож соседей не вызывает");
            }
        };
        PaymentClient payments = (orderId, amount, currency, sessionExpiresAt) -> {
            throw new UnsupportedOperationException("Сторож соседей не вызывает");
        };
        SagaOrchestrator saga = new SagaOrchestrator(catalog, inventory, payments, transitions, settings, clock, meters);
        return new OrderWatchdog(orders, saga, watchdogSettings, clock, meters);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 300 + n));
    }

    private StoredOrder created(int n, Instant createdAt) {
        StoredOrder order = new StoredOrder(id(n), 1000L + n, BUYER, SELLER, PRODUCT, "Лунная тропа: ключ Steam", 2, 149_900, 299_800, 5_996, "RUB",
                OrderStatus.CREATED, null, "buyer@mail.example", null, null, null, createdAt, null, null);
        store.seed(order);
        return order;
    }

    private StoredOrder awaiting(int n, Instant reserveUntil) {
        StoredOrder order = new StoredOrder(id(n), 1000L + n, BUYER, SELLER, PRODUCT, "Лунная тропа: ключ Steam", 2, 149_900, 299_800, 5_996, "RUB",
                OrderStatus.AWAITING_PAYMENT, null, "buyer@mail.example", reserveUntil, reserveUntil.minus(Duration.ofMinutes(3)),
                "https://localhost:8443/payment/pay/pay_1", reserveUntil.minus(Duration.ofMinutes(15)), null, null);
        store.seed(order);
        return order;
    }

    private double repairs(String kind) {
        var counter = meters.find("orders_watchdog_repairs_total").tag("kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }

    @Tag("TC-519")
    @Test
    void createdOrderOlderThanSixtySecondsIsCancelledAsSystemErrorAndTheEventFreesTheReserve() {
        created(1, T0.minusSeconds(61));

        assertEquals(1, watchdog.runOnce());

        StoredOrder order = store.get(id(1));
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(CancelReason.SYSTEM_ERROR, order.cancelReason());
        assertEquals(List.of("order.cancelled"), store.events().stream().map(FakeOrderStore.Published::eventType).toList());
        assertEquals(CancelReason.SYSTEM_ERROR, store.events().get(0).data().get("cancelReason"));
        assertEquals(1.0, meters.find("orders_cancelled_total").tag("transition", "T12").counter().count());
        assertEquals(1.0, repairs("abandoned-created"));
    }

    @Tag("TC-519")
    @Test
    void createdOrderYoungerThanOrExactlySixtySecondsOldIsLeftAlone() {
        created(1, T0.minusSeconds(60));
        created(2, T0.minusSeconds(10));

        assertEquals(0, watchdog.runOnce());

        assertEquals(OrderStatus.CREATED, store.get(id(1)).status(), "граница строгая: ровно 60 секунд это ещё не старше");
        assertEquals(OrderStatus.CREATED, store.get(id(2)).status());
        assertEquals(0, store.events().size());
    }

    @Tag("TC-519")
    @Test
    void awaitingOrderWhoseReserveEndedMoreThanFiveMinutesAgoIsCancelledByT6() {
        awaiting(1, T0.minus(Duration.ofMinutes(5)).minusSeconds(1));

        assertEquals(1, watchdog.runOnce());

        StoredOrder order = store.get(id(1));
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(CancelReason.RESERVATION_EXPIRED, order.cancelReason());
        assertEquals(1, store.events("order.cancelled").size());
        assertEquals(1.0, meters.find("orders_cancelled_total").tag("transition", "T6").counter().count());
        assertEquals(1.0, repairs("lost-reservation-expired"));
    }

    @Tag("TC-519")
    @Test
    void awaitingOrderWithTheReserveAliveOrEndedLessThanFiveMinutesAgoIsLeftAloneSoTheEventStaysTheFirstWay() {
        awaiting(1, T0.plus(Duration.ofMinutes(10)));
        awaiting(2, T0.minus(Duration.ofMinutes(4)));
        awaiting(3, T0.minus(Duration.ofMinutes(5)));

        assertEquals(0, watchdog.runOnce());

        for (int n = 1; n <= 3; n++) {
            assertEquals(OrderStatus.AWAITING_PAYMENT, store.get(id(n)).status(), "заказ " + n);
        }
        assertEquals(0, store.events().size());
    }

    @Tag("TC-519")
    @Test
    void ordersInOtherStatusesAreNeverTouched() {
        Instant old = T0.minus(Duration.ofHours(2));
        for (String status : List.of(OrderStatus.PAID, OrderStatus.ISSUED, OrderStatus.CANCELLED, OrderStatus.REFUNDED)) {
            int n = store.all().size() + 1;
            store.seed(new StoredOrder(id(n), 1000L + n, BUYER, SELLER, PRODUCT, "Игра", 1, 100_00, 100_00, 2_00, "RUB", status,
                    OrderStatus.CANCELLED.equals(status) ? CancelReason.PAYMENT_DECLINED : null, "buyer@mail.example", old, old, null, old, old, old));
        }

        assertEquals(0, watchdog.runOnce());

        assertEquals(0, store.events().size());
    }

    @Tag("TC-519")
    @Test
    void secondPassFindsNothingAndTheSameEventIsNotPublishedTwice() {
        created(1, T0.minusSeconds(90));
        awaiting(2, T0.minus(Duration.ofMinutes(6)));

        assertEquals(2, watchdog.runOnce());
        assertEquals(0, watchdog.runOnce());

        assertEquals(2, store.events("order.cancelled").size());
    }

    @Tag("TC-519")
    @Test
    void anEventThatWonTheRaceLeavesNothingForTheWatchdog() {
        awaiting(1, T0.minus(Duration.ofMinutes(6)));
        List<UUID> found = repo.findAwaitingReserveEndedBefore(T0.minus(Duration.ofMinutes(5)), 10);
        assertEquals(List.of(id(1)), found);

        assertTrue(transitions.cancel(id(1), CancelReason.RESERVATION_EXPIRED).isPresent(), "reservation.expired пришло первым");
        assertEquals(0, watchdog.runOnce());

        assertEquals(1, store.events("order.cancelled").size(), "сторож второго события не рождает");
    }

    @Tag("TC-519")
    @Test
    void anOrderPaidBetweenTheSearchAndTheTransitionIsNotCancelled() {
        awaiting(1, T0.minus(Duration.ofMinutes(6)));
        // выборка сторожа уже вернула заказ, оплата успела раньше перехода: статус перепроверяется под блокировкой строки
        OrderWatchdog stale = newWatchdog(new Repo() {
            @Override
            public List<UUID> findAwaitingReserveEndedBefore(Instant border, int limit) {
                List<UUID> found = List.of(id(1));
                assertTrue(transitions.pay(id(1), T0).isPresent());
                return found;
            }
        }, WatchdogSettings.defaults());

        assertEquals(0, stale.runOnce());

        assertEquals(OrderStatus.PAID, store.get(id(1)).status());
        assertEquals(0, store.events("order.cancelled").size());
    }

    @Tag("TC-519")
    @Test
    void aFailureOnOneOrderDoesNotStopTheOthersAndTheNextPassPicksItUp() {
        created(1, T0.minusSeconds(120));
        created(2, T0.minusSeconds(100));
        created(3, T0.minusSeconds(80));
        store.failOnPublishOf(id(1));

        assertEquals(2, watchdog.runOnce(), "заказы 2 и 3 исправлены, заказ 1 остался как был");
        assertEquals(OrderStatus.CREATED, store.get(id(1)).status(), "статус и событие меняются вместе: откатилось всё");
        assertEquals(OrderStatus.CANCELLED, store.get(id(2)).status());
        assertEquals(OrderStatus.CANCELLED, store.get(id(3)).status());
        assertEquals(2, store.events("order.cancelled").size());

        store.publishWorks();
        assertEquals(1, watchdog.runOnce());
        assertEquals(OrderStatus.CANCELLED, store.get(id(1)).status());
        assertEquals(3, store.events("order.cancelled").size());
        assertEquals(3.0, repairs("abandoned-created"));
    }

    @Tag("TC-519")
    @Test
    void theRepositoryFailingFailsThePassButTheScheduledEntryPointSwallowsIt() {
        repo.failure = new IllegalStateException("база недоступна");

        assertThrows(IllegalStateException.class, () -> watchdog.runOnce());
        watchdog.scheduled();

        repo.failure = null;
        created(1, T0.minusSeconds(61));
        watchdog.scheduled();
        assertEquals(OrderStatus.CANCELLED, store.get(id(1)).status(), "следующий проход подбирает то, что не удалось");
    }

    @Tag("TC-519")
    @Test
    void thePassTakesNoMoreThanTheBatchOfEachKindOldestFirst() {
        OrderWatchdog small = newWatchdog(repo, new WatchdogSettings(Duration.ofSeconds(60), Duration.ofMinutes(5), 2));
        created(1, T0.minusSeconds(300));
        created(2, T0.minusSeconds(200));
        created(3, T0.minusSeconds(100));

        assertEquals(2, small.runOnce());
        assertEquals(OrderStatus.CANCELLED, store.get(id(1)).status());
        assertEquals(OrderStatus.CANCELLED, store.get(id(2)).status());
        assertEquals(OrderStatus.CREATED, store.get(id(3)).status());

        assertEquals(1, small.runOnce());
        assertEquals(OrderStatus.CANCELLED, store.get(id(3)).status());
    }

    @Tag("TC-519")
    @Test
    void theThresholdsFollowTheClockAndTheSettingsNotTheCode() {
        created(1, T0.minusSeconds(20));
        assertEquals(0, watchdog.runOnce());

        clock.advance(Duration.ofSeconds(41));
        assertEquals(1, watchdog.runOnce(), "через 61 секунду после создания заказ уже старше порога");

        OrderWatchdog quick = newWatchdog(repo, new WatchdogSettings(Duration.ofSeconds(5), Duration.ofSeconds(5), 10));
        awaiting(2, clock.instant().minusSeconds(6));
        assertEquals(1, quick.runOnce(), "под fast-time запас после конца резерва 5 секунд");
    }

    @Tag("TC-519")
    @Test
    void settingsRejectNonsense() {
        assertThrows(IllegalStateException.class, () -> new WatchdogSettings(Duration.ZERO, Duration.ofMinutes(5), 100));
        assertThrows(IllegalStateException.class, () -> new WatchdogSettings(null, Duration.ofMinutes(5), 100));
        assertThrows(IllegalStateException.class, () -> new WatchdogSettings(Duration.ofSeconds(-1), Duration.ofMinutes(5), 100));
        assertThrows(IllegalStateException.class, () -> new WatchdogSettings(Duration.ofSeconds(60), Duration.ofSeconds(-1), 100));
        assertThrows(IllegalStateException.class, () -> new WatchdogSettings(Duration.ofSeconds(60), null, 100));
        assertThrows(IllegalStateException.class, () -> new WatchdogSettings(Duration.ofSeconds(60), Duration.ofMinutes(5), 0));
        assertThrows(IllegalStateException.class, () -> new WatchdogSettings(Duration.ofSeconds(60), Duration.ofMinutes(5), 501));

        WatchdogSettings defaults = WatchdogSettings.defaults();
        assertEquals(Duration.ofSeconds(60), defaults.createdTimeout());
        assertEquals(Duration.ofMinutes(5), defaults.reserveGrace());
        assertEquals(100, defaults.batch());
        assertEquals(Duration.ZERO, new WatchdogSettings(Duration.ofSeconds(1), Duration.ZERO, 1).reserveGrace());
    }
}
