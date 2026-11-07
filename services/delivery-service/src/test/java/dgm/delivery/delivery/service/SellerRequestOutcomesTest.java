package dgm.delivery.delivery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.delivery.delivery.FakeSellerRequests;
import dgm.delivery.delivery.FakeTransaction;
import dgm.delivery.delivery.FakeTransaction.Published;
import dgm.delivery.delivery.domain.PendingRequest;
import dgm.delivery.delivery.domain.SellerRequestSeed;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Исходы запроса продавцу (TC-963, TC-965, TC-966, ADR-028): повторы с паузами 5 и 30 секунд и тайм-аут после третьей неудачи; отказ без повтора и
 * закрытие окна закрывают запрос, выдачу и контроль и пишут одно событие {@code delivery.seller-timeout} (без {@code delivery.failed}); поздний или
 * повторный исход ничего не меняет; запрос с арендой на приём ответа тайм-аут не берёт.
 */
@Tag("TC-963")
@Tag("TC-965")
@Tag("TC-966")
class SellerRequestOutcomesTest {

    private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
    private static final UUID DELIVERY = UUID.fromString("0199e0a0-0000-7000-8000-000000000a01");
    private static final UUID REQUEST = UUID.fromString("0199e0a0-0000-7000-8000-000000000b01");
    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private FakeDeliveries deliveries;
    private FakeSellerRequests requests;
    private FakeTransaction transaction;
    private ManualClock clock;
    private SimpleMeterRegistry meters;
    private SellerRequestOutcomes outcomes;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        requests = new FakeSellerRequests(deliveries);
        transaction = new FakeTransaction();
        clock = new ManualClock(T0);
        meters = new SimpleMeterRegistry();
        outcomes = new SellerRequestOutcomes(requests, deliveries, transaction, SellerSettings.defaults(), clock, meters);
        deliveries.insertPrimary(DELIVERY, ORDER, BUYER, "anna@mail.example", "Игра", 2, T0);
        deliveries.insertWatch(ORDER, T0.plus(Duration.ofMinutes(30)), T0);
        requests.insert(DELIVERY, REQUEST, new SellerRequestSeed(SELLER, PRODUCT, 1042), T0.plus(WINDOW), T0);
    }

    /** Запрос, взятый отправителем в срок его следующей отправки. */
    private PendingRequest claim() {
        List<PendingRequest> due = requests.claimDue(clock.instant(), Duration.ofMinutes(1), 20);
        assertEquals(1, due.size(), "запрос должен быть в очереди отправки");
        return due.get(0);
    }

    @Test
    void theAnswer2xxMovesTheRequestToAcceptedAndCountsTheSend() {
        PendingRequest request = claim();

        assertTrue(outcomes.accepted(request));

        FakeSellerRequests.Row row = requests.row(DELIVERY);
        assertEquals("accepted", row.status);
        assertEquals(1, row.attempts);
        assertNull(row.nextAttemptAt, "принятый запрос повторно не отправляется");
        assertTrue(transaction.published().isEmpty(), "приём запроса события не пишет");
    }

    @Test
    void aSendThatLostTheRaceToTheAnswerOfTheSellerChangesNothing() {
        PendingRequest request = claim();
        requests.answered(REQUEST, clock.instant());

        assertFalse(outcomes.accepted(request), "ответ с ключами обогнал подтверждение");

        assertEquals("answered", requests.row(DELIVERY).status);
    }

    @Test
    void theRepeatsKeepTheScheduleFiveAndThirtySecondsAndTheThirdFailureLeavesTheRequestToTheTimeout() {
        PendingRequest first = claim();
        assertTrue(outcomes.retry(first, "seller_http_503"));
        FakeSellerRequests.Row row = requests.row(DELIVERY);
        assertEquals("requested", row.status);
        assertEquals(1, row.attempts);
        assertEquals(T0.plusSeconds(5), row.nextAttemptAt);

        clock.advance(Duration.ofSeconds(5));
        PendingRequest second = claim();
        assertEquals(2, second.nextAttemptNo());
        assertTrue(outcomes.retry(second, "seller_timeout"));
        assertEquals(2, row.attempts);
        assertEquals(clock.instant().plusSeconds(30), row.nextAttemptAt);

        clock.advance(Duration.ofSeconds(30));
        PendingRequest third = claim();
        assertEquals(3, third.nextAttemptNo());
        assertTrue(outcomes.retry(third, "seller_io"));
        assertEquals(3, row.attempts);
        assertNull(row.nextAttemptAt, "после третьей неудачи запроса ждёт тайм-аут, четвёртой отправки нет");
        assertEquals("requested", row.status);
        assertTrue(requests.claimDue(clock.instant().plus(Duration.ofHours(1)), Duration.ofMinutes(1), 20).isEmpty());
    }

    @Test
    void aRepeatThatWouldFallAfterTheDeadlineIsNotScheduled() {
        UUID delivery = UUID.fromString("0199e0a0-0000-7000-8000-000000000a02");
        UUID order = UUID.fromString("0199e0a0-0000-7000-8000-000000000302");
        deliveries.insertPrimary(delivery, order, BUYER, "anna@mail.example", "Игра", 1, T0);
        requests.insert(delivery, UUID.fromString("0199e0a0-0000-7000-8000-000000000b02"), new SellerRequestSeed(SELLER, PRODUCT, 1043), T0.plusSeconds(20), T0);
        PendingRequest first = requests.claimDue(T0, Duration.ofMinutes(1), 20).stream().filter(r -> r.deliveryId().equals(delivery)).findFirst().orElseThrow();
        assertTrue(outcomes.retry(first, "seller_http_500"));
        assertEquals(T0.plusSeconds(5), requests.row(delivery).nextAttemptAt, "5 секунд укладываются в окно");

        clock.advance(Duration.ofSeconds(5));
        PendingRequest second = requests.claimDue(clock.instant(), Duration.ofMinutes(1), 20).stream().filter(r -> r.deliveryId().equals(delivery)).findFirst()
                .orElseThrow();
        assertTrue(outcomes.retry(second, "seller_http_500"));

        assertNull(requests.row(delivery).nextAttemptAt, "30 секунд после неё уже за окном: повтор не назначается");
    }

    @Test
    void aRefusalClosesTheRequestTheDeliveryAndTheWatchWithOneEventAndWithoutDeliveryFailed() {
        PendingRequest request = claim();
        clock.advance(Duration.ofSeconds(7));

        assertTrue(outcomes.fail(request, "seller_rejected", true));

        FakeSellerRequests.Row row = requests.row(DELIVERY);
        assertEquals("failed", row.status);
        assertEquals("seller_rejected", row.errorCode);
        assertEquals(1, row.attempts, "отправка состоялась и посчитана");
        FakeDeliveries.Row delivery = deliveries.row(DELIVERY);
        assertEquals("failed", delivery.status);
        assertEquals("seller_unavailable", delivery.failReason);
        assertEquals("seller_rejected", delivery.lastErrorCode);
        assertEquals("closed", deliveries.watchStatus(ORDER), "контроль 30 минут закрыт: оповещения о недоставке не будет");
        assertTrue(deliveries.attempts().isEmpty(), "письма не было, попытка отправки не записывается");
        assertTrue(transaction.published(DeliveryEvents.FAILED).isEmpty(), "delivery.failed не публикуется");
        List<Published> events = transaction.published(DeliveryEvents.SELLER_TIMEOUT);
        assertEquals(1, events.size());
        Published event = events.get(0);
        assertEquals(DeliveryEvents.TOPIC, event.topic());
        assertEquals(ORDER.toString(), event.aggregateId());
        assertEquals(Set.of("deliveryId", "orderId", "sellerId", "waitedSeconds", "reason"), event.data().keySet(), "ни адреса, ни ключей, ни текста отказа");
        assertEquals("seller_error", event.data().get("reason"));
        assertEquals(7L, ((Number) event.data().get("waitedSeconds")).longValue());
        assertEquals(DELIVERY.toString(), event.data().get("deliveryId"));
        assertEquals(SELLER.toString(), event.data().get("sellerId"));
    }

    @Test
    void aRefusalWithoutASendDoesNotCountAnAttempt() {
        PendingRequest request = claim();

        assertTrue(outcomes.fail(request, "integration_missing", false));

        assertEquals(0, requests.row(DELIVERY).attempts, "запрос не уходил к продавцу");
        assertEquals("integration_missing", requests.row(DELIVERY).errorCode);
    }

    @Test
    void theSecondRefusalOfTheSameRequestDoesNotWriteASecondEvent() {
        PendingRequest request = claim();
        assertTrue(outcomes.fail(request, "seller_rejected", true));

        assertFalse(outcomes.fail(request, "seller_rejected", true));

        assertEquals(1, transaction.published(DeliveryEvents.SELLER_TIMEOUT).size());
    }

    @Test
    void aDeliveryThatIsNotQueuedCannotBeClosedBySellerErrorAndNoEventIsWritten() {
        PendingRequest request = claim();
        deliveries.row(DELIVERY).status = "sent";

        assertThrows(IllegalStateException.class, () -> outcomes.fail(request, "seller_rejected", true));

        assertTrue(transaction.published().isEmpty());
        assertEquals(1.0, meters.counter("delivery_anomalies_total", "reason", "seller_request_delivery_not_queued").count());
    }

    @Test
    void nothingIsClosedBeforeTheDeadline() {
        clock.advance(WINDOW.minusSeconds(1));

        assertEquals(0, outcomes.expireDue());

        assertEquals("requested", requests.row(DELIVERY).status);
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void whenTheWindowIsClosedTheTimeoutClosesTheRequestTheDeliveryAndTheWatchWithOneEvent() {
        clock.advance(WINDOW);

        assertEquals(1, outcomes.expireDue());

        assertEquals("timeout", requests.row(DELIVERY).status);
        FakeDeliveries.Row delivery = deliveries.row(DELIVERY);
        assertEquals("failed", delivery.status);
        assertEquals("seller_unavailable", delivery.failReason);
        assertEquals("seller_timeout", delivery.lastErrorCode);
        assertEquals("closed", deliveries.watchStatus(ORDER));
        assertTrue(transaction.published(DeliveryEvents.FAILED).isEmpty());
        List<Published> events = transaction.published(DeliveryEvents.SELLER_TIMEOUT);
        assertEquals(1, events.size());
        assertEquals("timeout", events.get(0).data().get("reason"));
        assertEquals(WINDOW.toSeconds(), ((Number) events.get(0).data().get("waitedSeconds")).longValue());
        assertEquals(1.0, meters.counter("delivery_seller_timeouts_total", "reason", "timeout").count());
    }

    @Test
    void aSecondPassOfTheTimeoutDoesNothing() {
        clock.advance(WINDOW);
        assertEquals(1, outcomes.expireDue());

        assertEquals(0, outcomes.expireDue());
        clock.advance(Duration.ofHours(1));
        assertEquals(0, outcomes.expireDue());

        assertEquals(1, transaction.published(DeliveryEvents.SELLER_TIMEOUT).size(), "исход один");
    }

    @Test
    void anAcceptedRequestWithoutAnAnswerAlsoTimesOut() {
        PendingRequest request = claim();
        assertTrue(outcomes.accepted(request));
        clock.advance(WINDOW);

        assertEquals(1, outcomes.expireDue());

        assertEquals("timeout", requests.row(DELIVERY).status);
    }

    @Test
    void aRequestWithTheLeaseOfAnAnswerIsLeftToFinishAndIsClosedOnlyAfterTheLeaseExpires() {
        clock.advance(WINDOW.minusSeconds(1));
        assertTrue(requests.lease(REQUEST, clock.instant(), clock.instant().plusSeconds(30)), "ответ начат до конца окна");
        clock.advance(Duration.ofSeconds(5));

        assertEquals(0, outcomes.expireDue(), "срок вышел, но ключи сохраняются: тайм-аут запрос не трогает");
        assertEquals("requested", requests.row(DELIVERY).status);
        assertTrue(transaction.published().isEmpty());

        clock.advance(Duration.ofSeconds(30));
        assertEquals(1, outcomes.expireDue(), "ответ не завершён за аренду: запрос закрывается тайм-аутом");
    }

    @Test
    void aRequestThatWasAnsweredIsNotClosedByTheTimeout() {
        requests.answered(REQUEST, clock.instant());
        clock.advance(WINDOW.plusMinutes(1));

        assertEquals(0, outcomes.expireDue());

        assertEquals("answered", requests.row(DELIVERY).status);
        assertEquals("queued", deliveries.row(DELIVERY).status);
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void theBatchOfTheTimeoutIsLimitedAndTheRestIsClosedByTheNextPass() {
        SellerSettings small = new SellerSettings(Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofSeconds(30), 20, 2);
        outcomes = new SellerRequestOutcomes(requests, deliveries, transaction, small, clock, meters);
        for (int i = 2; i <= 4; i++) {
            UUID delivery = new UUID(0x0199e0a000007000L, 0x8000000000000a00L + i);
            UUID order = new UUID(0x0199e0a000007000L, 0x8000000000000300L + i);
            deliveries.insertPrimary(delivery, order, BUYER, "anna@mail.example", "Игра", 1, T0);
            requests.insert(delivery, new UUID(0x0199e0a000007000L, 0x8000000000000b00L + i), new SellerRequestSeed(SELLER, PRODUCT, 1040 + i), T0.plus(WINDOW), T0);
        }
        clock.advance(WINDOW);

        assertEquals(2, outcomes.expireDue());
        assertEquals(2, outcomes.expireDue());
        assertEquals(0, outcomes.expireDue());

        assertEquals(4, transaction.published(DeliveryEvents.SELLER_TIMEOUT).size());
    }
}
