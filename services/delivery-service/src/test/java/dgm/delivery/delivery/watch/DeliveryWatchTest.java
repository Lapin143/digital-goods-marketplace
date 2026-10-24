package dgm.delivery.delivery.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.delivery.delivery.FakeTransaction;
import dgm.delivery.delivery.domain.ProviderStatus;
import dgm.delivery.delivery.domain.StatusNotice;
import dgm.delivery.delivery.service.DeliverySettings;
import dgm.delivery.delivery.service.DeliveryStatuses;
import dgm.delivery.delivery.service.StatusSettings;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Контроль доставки 30 минут (NFT-2.4, E11, ADR-011, TC-608): открытый контроль, срок которого наступил, а первичная выдача не «доставлено»,
 * передаётся в поддержку и пишет {@code delivery.overdue} один раз; статус выдачи не меняется; доставленная выдача закрывает контроль без
 * события; запоздавшая доставка закрывает передачу.
 */
@Tag("TC-608")
class DeliveryWatchTest {

    private static final Instant PAID = Instant.parse("2026-10-19T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(30);
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");

    private FakeDeliveries deliveries;
    private FakeTransaction transaction;
    private ManualClock clock;
    private SimpleMeterRegistry meters;
    private DeliveryWatch watch;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        transaction = new FakeTransaction();
        clock = new ManualClock(PAID);
        meters = new SimpleMeterRegistry();
        build(DeliverySettings.defaults(), StatusSettings.defaults());
    }

    private void build(DeliverySettings settings, StatusSettings statusSettings) {
        watch = new DeliveryWatch(deliveries, transaction, settings, statusSettings, clock, meters);
    }

    private static UUID id(int number) {
        return UUID.fromString("0199e0a0-0000-7000-8000-00000000" + String.format("%04x", number));
    }

    private static UUID orderId(int number) {
        return UUID.fromString("0199e0a0-0000-7000-8000-00000001" + String.format("%04x", number));
    }

    /** Отправленная выдача заказа, оплаченного в {@code paidAt}, с открытым контролем. */
    private UUID sent(int number, Instant paidAt, Duration window) {
        deliveries.addSent(id(number), orderId(number), BUYER, "anna@mail.example", "Игра", 1, 1, paidAt.plusSeconds(2));
        deliveries.insertWatch(orderId(number), paidAt.plus(window), paidAt);
        return id(number);
    }

    @Test
    void nothingHappensBeforeTheDeadline() {
        sent(1, PAID, WINDOW);
        clock.set(PAID.plus(WINDOW).minusSeconds(1));

        assertEquals(0, watch.runOnce());

        assertEquals("open", deliveries.watchStatus(orderId(1)));
        assertEquals(0, transaction.published().size());
    }

    @Test
    void anUndeliveredPrimaryDeliveryAtTheDeadlineIsHandedOverWithOneOverdueEventAndItsStatusIsUnchanged() {
        sent(1, PAID, WINDOW);
        clock.set(PAID.plus(WINDOW));

        assertEquals(1, watch.runOnce());

        assertEquals("handed_over", deliveries.watchStatus(orderId(1)));
        assertEquals("sent", deliveries.row(id(1)).status, "контроль статус выдачи не меняет (SM-06)");
        List<FakeTransaction.Published> events = transaction.published("delivery.overdue");
        assertEquals(1, events.size());
        FakeTransaction.Published event = events.get(0);
        assertEquals("delivery.events", event.topic());
        assertEquals("Delivery", event.aggregateType());
        assertEquals(orderId(1).toString(), event.aggregateId());
        assertEquals(Map.of("deliveryId", id(1).toString(), "orderId", orderId(1).toString(), "paidAt", "2026-10-19T12:00:00.000Z", "thresholdSeconds", 1800L),
                event.data());
        assertEquals(1, meters.counter("delivery_overdue_total").count());
    }

    @Test
    void theOverdueEventIsPublishedOnceHoweverManyTimesThePassRuns() {
        sent(1, PAID, WINDOW);
        clock.set(PAID.plus(WINDOW).plusSeconds(5));

        assertEquals(1, watch.runOnce());
        assertEquals(0, watch.runOnce());
        clock.advance(Duration.ofMinutes(10));
        assertEquals(0, watch.runOnce());

        assertEquals(1, transaction.published("delivery.overdue").size());
        assertEquals(1, meters.counter("delivery_overdue_total").count());
    }

    @Test
    void aDeliveryStillInTheQueueOrFailedIsAlsoHandedOverBecauseTheOrderIsNotDelivered() {
        deliveries.insertPrimary(id(1), orderId(1), BUYER, "anna@mail.example", "Игра", 1, PAID);
        deliveries.insertWatch(orderId(1), PAID.plus(WINDOW), PAID);
        deliveries.addSent(id(2), orderId(2), BUYER, "anna@mail.example", "Игра", 1, 1, PAID);
        deliveries.insertWatch(orderId(2), PAID.plus(WINDOW), PAID);
        deliveries.markUndelivered(id(2), "address_rejected", "provider_other");
        clock.set(PAID.plus(WINDOW));

        assertEquals(2, watch.runOnce());

        assertEquals("queued", deliveries.row(id(1)).status);
        assertEquals("failed", deliveries.row(id(2)).status);
        assertEquals(2, transaction.published("delivery.overdue").size());
    }

    @Test
    void aDeliveredPrimaryDeliveryClosesTheWatchWithoutAnEvent() {
        sent(1, PAID, WINDOW);
        deliveries.markDelivered(id(1), PAID.plusSeconds(20));
        clock.set(PAID.plus(WINDOW));

        assertEquals(0, watch.runOnce());

        assertEquals("closed", deliveries.watchStatus(orderId(1)));
        assertEquals(0, transaction.published().size());
        assertEquals(0, meters.counter("delivery_overdue_total").count());
    }

    @Test
    void thePassTakesTheDueWatchesInTheOrderOfTheDeadlineInBatches() {
        build(DeliverySettings.defaults(), new StatusSettings(Duration.ofMinutes(5), 50, 2));
        sent(3, PAID.minusSeconds(30), WINDOW);
        sent(1, PAID.minusSeconds(90), WINDOW);
        sent(2, PAID.minusSeconds(60), WINDOW);
        sent(4, PAID.plusSeconds(600), WINDOW);
        clock.set(PAID.plus(WINDOW));

        assertEquals(2, watch.runOnce());
        assertEquals(List.of(orderId(1).toString(), orderId(2).toString()), transaction.published("delivery.overdue").stream().map(FakeTransaction.Published::aggregateId).toList(),
                "сначала самые давние");
        assertEquals(1, watch.runOnce());
        assertEquals(0, watch.runOnce());

        assertEquals(3, transaction.published("delivery.overdue").size());
        assertEquals("open", deliveries.watchStatus(orderId(4)), "срок четвёртого ещё не наступил");
        assertEquals(List.of(2, 2, 2), deliveries.watchLimits());
    }

    @Test
    void aWatchWithoutAPrimaryDeliveryIsLeftAloneAndCounted() {
        deliveries.insertWatch(orderId(9), PAID.plus(WINDOW), PAID);
        clock.set(PAID.plus(WINDOW));

        assertEquals(0, watch.runOnce());

        assertEquals("open", deliveries.watchStatus(orderId(9)));
        assertEquals(0, transaction.published().size());
        assertEquals(1, meters.counter("delivery_anomalies_total", "reason", "watch_without_delivery").count());
    }

    @Test
    void theWindowOfTheFastTimeProfileIsReflectedInThePaymentMomentAndTheThreshold() {
        Duration fast = Duration.ofMinutes(3);
        build(new DeliverySettings(Duration.ofMinutes(2), 20, fast), StatusSettings.defaults());
        sent(1, PAID, fast);
        clock.set(PAID.plus(fast));

        assertEquals(1, watch.runOnce());

        Map<String, Object> data = transaction.published("delivery.overdue").get(0).data();
        assertEquals("2026-10-19T12:00:00.000Z", data.get("paidAt"));
        assertEquals(180L, data.get("thresholdSeconds"));
    }

    @Test
    void aLateDeliveryAfterTheHandOverClosesTheWatchAndTheNextPassDoesNothing() {
        UUID delivery = sent(1, PAID, WINDOW);
        clock.set(PAID.plus(WINDOW).plusSeconds(30));
        assertEquals(1, watch.runOnce());
        DeliveryStatuses statuses = new DeliveryStatuses(deliveries, transaction, DeliverySettings.defaults(), clock, meters);

        assertEquals(DeliveryStatuses.Result.APPLIED, statuses.apply(new StatusNotice(delivery.toString(), ProviderStatus.DELIVERED, clock.instant(), null)));

        assertEquals("closed", deliveries.watchStatus(orderId(1)));
        assertEquals("delivered", deliveries.row(delivery).status);
        assertEquals(0, watch.runOnce());
        assertEquals(1, transaction.published("delivery.overdue").size());
        assertEquals(1, transaction.published("delivery.delivered").size());
        assertTrue(deliveries.watchLimits().size() >= 2);
    }
}
