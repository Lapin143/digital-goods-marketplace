package dgm.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderFilter;
import dgm.order.orders.domain.OrderSession;
import dgm.order.orders.repository.OrderRepository;
import dgm.order.orders.service.SagaOrchestrator;
import dgm.order.orders.watchdog.OrderWatchdog;
import dgm.order.orders.watchdog.WatchdogSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Сторож заказов на стенде (ADR-004, SM-01/T6 и T12): настоящие выборки по частичным индексам базы, оркестратор и Outbox. Заказы с нужным
 * возрастом вставляются прямо в базу (дата создания неизменна, а потерянное событие иначе не воспроизвести). Фоновый запуск в тестах отключён
 * (интервал час), поэтому проход делает тест и только по своим заказам: чужие заказы параллельных тестов и тестовых данных он не трогает.
 */
class OrderWatchdogIT extends OrderStandIT {

    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");

    @Autowired
    private OrderRepository repository;
    @Autowired
    private SagaOrchestrator saga;
    @Autowired
    private WatchdogSettings settings;
    @Autowired
    private Clock clock;
    @Autowired
    private MeterRegistry meters;

    /** Настоящие выборки, но только заказы теста: выборка берётся целиком и режется до лимита уже после отбора. */
    private OrderWatchdog watchdogFor(Set<UUID> mine) {
        OrderRepository filtered = new OrderRepository() {
            @Override
            public List<UUID> findCreatedBefore(Instant border, int limit) {
                return repository.findCreatedBefore(border, 100_000).stream().filter(mine::contains).limit(limit).toList();
            }

            @Override
            public List<UUID> findAwaitingReserveEndedBefore(Instant border, int limit) {
                return repository.findAwaitingReserveEndedBefore(border, 100_000).stream().filter(mine::contains).limit(limit).toList();
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
        };
        return new OrderWatchdog(filtered, saga, settings, clock, meters);
    }

    /** Заказ «создан», созданный {@code ageSeconds} секунд назад. */
    private static UUID created(long ageSeconds) {
        UUID id = UUID.randomUUID();
        db.update("insert into orders.orders (id, buyer_id, seller_id, product_id, product_title, quantity, unit_price, amount, commission_rate_bp, "
                + "commission, delivery_address, created_at) values (?, ?, ?, ?, 'Игра сторожа', 2, 149900, 299800, 200, 5996, 'buyer@mail.example', "
                + "now() - make_interval(secs => ?::double precision))", id, UUID.randomUUID(), SELLER, PRODUCT, ageSeconds);
        return id;
    }

    /** Заказ «ожидает оплаты», резерв которого закончился {@code endedSecondsAgo} секунд назад (отрицательное число: закончится в будущем). */
    private static UUID awaiting(long endedSecondsAgo) {
        UUID id = created(endedSecondsAgo + 1_800);
        db.update("update orders.orders set status = 'awaiting_payment', reserve_until = now() - make_interval(secs => ?::double precision), "
                + "session_until = now() - make_interval(secs => ?::double precision), payment_session_url = 'https://pay.stub.example/session/watchdog' where id = ?",
                endedSecondsAgo, endedSecondsAgo + 180, id);
        return id;
    }

    @Test
    void settingsOfTheStandAreTheDefaultsOfTheArchitecture() {
        assertEquals(WatchdogSettings.defaults(), settings, "60 секунд для «создан», 5 минут после конца резерва");
    }

    @Tag("TC-519")
    @Test
    void anOrderLeftInCreatedForMoreThanSixtySecondsIsCancelledAsASystemErrorAndTheReserveIsFreedByTheEvent() {
        UUID lost = created(180);
        UUID fresh = created(10);

        int repaired = watchdogFor(Set.of(lost, fresh)).runOnce();

        assertEquals(1, repaired);
        assertEquals("cancelled", status(lost.toString()));
        assertEquals("system_error", order(lost.toString()).get("cancel_reason"));
        List<java.util.Map<String, Object>> cancelled = events(lost.toString(), "order.cancelled");
        assertEquals(1, cancelled.size());
        assertEquals("order.events", cancelled.get(0).get("topic"));
        assertEquals("system_error", data(cancelled.get(0)).get("cancelReason"));
        assertEquals("created", status(fresh.toString()), "моложе 60 секунд сторож не трогает");
        assertEquals(0, eventCount(fresh.toString()));
    }

    @Tag("TC-519")
    @Test
    void anAwaitingOrderWhoseReserveEndedMoreThanFiveMinutesAgoIsCancelledByTimeAndOthersAreLeftAlone() {
        UUID lost = awaiting(600);
        UUID grace = awaiting(120);
        UUID alive = awaiting(-900);

        int repaired = watchdogFor(Set.of(lost, grace, alive)).runOnce();

        assertEquals(1, repaired);
        assertEquals("cancelled", status(lost.toString()));
        assertEquals("reservation_expired", order(lost.toString()).get("cancel_reason"));
        assertEquals(1, events(lost.toString(), "order.cancelled").size());
        assertEquals("awaiting_payment", status(grace.toString()), "запас 5 минут: событие истечения ещё может прийти само");
        assertEquals("awaiting_payment", status(alive.toString()));
        assertEquals(0, eventCount(grace.toString()) + eventCount(alive.toString()));
    }

    @Tag("TC-519")
    @Test
    void aSecondPassFindsNothingAndTheRepairIsCounted() throws IOException {
        UUID lostCreated = created(300);
        UUID lostAwaiting = awaiting(900);
        OrderWatchdog watchdog = watchdogFor(Set.of(lostCreated, lostAwaiting));

        assertEquals(2, watchdog.runOnce());
        assertEquals(0, watchdog.runOnce());

        assertEquals(1, events(lostCreated.toString(), "order.cancelled").size());
        assertEquals(1, events(lostAwaiting.toString(), "order.cancelled").size());
        String metrics = monitoring.get("/actuator/prometheus").body();
        assertTrue(metrics.contains("orders_watchdog_repairs_total"), "исправления сторожа видны в метриках");
        assertNotNull(meters.find("orders_watchdog_repairs_total").tag("kind", "abandoned-created").counter());
        assertNotNull(meters.find("orders_watchdog_repairs_total").tag("kind", "lost-reservation-expired").counter());
    }

    @Tag("TC-519")
    @Test
    void theSelectionsUseTheirOwnOrderOldestFirstAndHonourTheLimit() {
        UUID older = created(900);
        UUID newer = created(400);
        UUID middle = created(600);
        Set<UUID> mine = Set.of(older, newer, middle);

        List<UUID> found = repository.findCreatedBefore(Instant.now().minusSeconds(60), 100_000).stream().filter(mine::contains).toList();
        assertEquals(List.of(older, middle, newer), found, "самые старые первыми");

        assertEquals(1, repository.findCreatedBefore(Instant.now().minusSeconds(60), 1).size(), "лимит выборки соблюдается");
    }
}
