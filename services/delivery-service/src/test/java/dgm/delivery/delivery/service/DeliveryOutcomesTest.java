package dgm.delivery.delivery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.delivery.delivery.FakeDeliveries.Row;
import dgm.delivery.delivery.FakeTransaction;
import dgm.delivery.delivery.FakeTransaction.Published;
import dgm.delivery.delivery.domain.AttemptResult;
import dgm.delivery.delivery.domain.DeliverySchedule;
import dgm.delivery.delivery.domain.QueuedDelivery;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Запись исхода попытки (SM-06/T2, T3, T4, ADR-011): принято, повторяемая ошибка по расписанию, шестая неудача, окончательная ошибка, поздний
 * исход после того, как выдачу изменил другой исполнитель. Событие и попытка пишутся вместе, адреса и значений ключей в них нет.
 */
class DeliveryOutcomesTest {

    private static final Instant T0 = Instant.parse("2026-10-19T12:00:00.123Z");
    private static final UUID ID = UUID.fromString("0199e0a0-0000-7000-8000-000000000a01");
    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");

    private FakeDeliveries deliveries;
    private FakeTransaction transaction;
    private ManualClock clock;
    private SimpleMeterRegistry meters;
    private DeliveryOutcomes outcomes;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        transaction = new FakeTransaction();
        clock = new ManualClock(T0);
        meters = new SimpleMeterRegistry();
        outcomes = new DeliveryOutcomes(deliveries, transaction, DeliverySchedule.parse("10s,30s,2m,5m,10m", 0.2, new Random(3)), clock, meters);
    }

    private QueuedDelivery claim() {
        return claimAfter(0);
    }

    /** Выдача, у которой уже {@code done} неудачных попыток, снова взятая в аренду в срок следующей попытки. */
    private QueuedDelivery claimAfter(int done) {
        deliveries.insertPrimary(ID, ORDER, BUYER, "anna@mail.example", "Игра", 2, T0);
        QueuedDelivery current = deliveries.claimDue(T0, Duration.ofMinutes(2), 20).get(0);
        for (int i = 0; i < done; i++) {
            assertTrue(outcomes.recordAttempt(current, new AttemptResult.Retryable("provider_unavailable"), T0));
            current = deliveries.claimDue(deliveries.row(ID).nextAttemptAt, Duration.ofMinutes(2), 20).get(0);
        }
        return current;
    }

    @Tag("TC-603")
    @Test
    void anAcceptedLetterMakesTheDeliverySentAndPublishesOneEvent() {
        QueuedDelivery delivery = claim();
        clock.advance(Duration.ofMillis(800));

        assertTrue(outcomes.recordAttempt(delivery, new AttemptResult.Accepted(), T0));

        Row row = deliveries.row(ID);
        assertEquals("sent", row.status);
        assertEquals(1, row.attemptNo);
        assertEquals(T0.plusMillis(800), row.sentAt);
        assertNull(row.nextAttemptAt);
        assertEquals(1, deliveries.attempts().size());
        assertEquals("accepted", deliveries.attempts().get(0).outcome());
        assertNull(deliveries.attempts().get(0).errorCode());
        List<Published> events = transaction.published();
        assertEquals(1, events.size());
        Published event = events.get(0);
        assertEquals("delivery.events", event.topic());
        assertEquals("Delivery", event.aggregateType());
        assertEquals(ORDER.toString(), event.aggregateId());
        assertEquals("delivery.accepted", event.eventType());
        assertEquals(1, event.schemaVersion());
        assertEquals(List.of("deliveryId", "orderId", "deliveryType", "attemptNo", "acceptedAt"), List.copyOf(event.data().keySet()));
        assertEquals(ID.toString(), event.data().get("deliveryId"));
        assertEquals(ORDER.toString(), event.data().get("orderId"));
        assertEquals("primary", event.data().get("deliveryType"));
        assertEquals(1, event.data().get("attemptNo"));
        assertEquals("2026-10-19T12:00:00.923Z", event.data().get("acceptedAt"));
        assertEquals(1.0, meters.counter("delivery_attempts_total", "outcome", "accepted").count());
    }

    @Tag("TC-604")
    @Test
    void aTemporaryErrorKeepsTheDeliveryInTheQueueUntilTheNextPauseOfTheSchedule() {
        QueuedDelivery delivery = claim();

        assertTrue(outcomes.recordAttempt(delivery, new AttemptResult.Retryable("provider_timeout"), T0));

        Row row = deliveries.row(ID);
        assertEquals("queued", row.status);
        assertEquals(1, row.attemptNo);
        assertEquals("provider_timeout", row.lastErrorCode);
        Duration pause = Duration.between(T0, row.nextAttemptAt);
        assertTrue(pause.compareTo(Duration.ofSeconds(8)) >= 0 && pause.compareTo(Duration.ofSeconds(12)) <= 0, "пауза 10 секунд ±20 %: " + pause);
        assertEquals("retryable_error", deliveries.attempts().get(0).outcome());
        assertEquals("provider_timeout", deliveries.attempts().get(0).errorCode());
        assertEquals(0, transaction.published().size(), "повтор события не публикует");
        assertEquals(1.0, meters.counter("delivery_attempts_total", "outcome", "retryable_error").count());
    }

    @Tag("TC-604")
    @Test
    void thePausesGrowWithEachFailure() {
        QueuedDelivery first = claim();
        outcomes.recordAttempt(first, new AttemptResult.Retryable("provider_unavailable"), T0);
        Duration afterFirst = Duration.between(T0, deliveries.row(ID).nextAttemptAt);

        QueuedDelivery second = deliveries.claimDue(deliveries.row(ID).nextAttemptAt, Duration.ofMinutes(2), 20).get(0);
        assertEquals(1, second.attemptNo());
        outcomes.recordAttempt(second, new AttemptResult.Retryable("provider_unavailable"), T0);
        Duration afterSecond = Duration.between(T0, deliveries.row(ID).nextAttemptAt);

        assertTrue(afterSecond.compareTo(afterFirst) > 0, afterFirst + " и " + afterSecond);
        assertTrue(afterSecond.compareTo(Duration.ofSeconds(24)) >= 0 && afterSecond.compareTo(Duration.ofSeconds(36)) <= 0, "30 секунд ±20 %: " + afterSecond);
        assertEquals(2, deliveries.row(ID).attemptNo);
    }

    @Tag("TC-604")
    @Test
    void theSixthFailureMakesTheDeliveryFailedWithOneEvent() {
        QueuedDelivery sixth = claimAfter(5);
        assertEquals(5, sixth.attemptNo());

        assertTrue(outcomes.recordAttempt(sixth, new AttemptResult.Retryable("provider_unavailable"), T0));

        Row row = deliveries.row(ID);
        assertEquals("failed", row.status);
        assertEquals(6, row.attemptNo);
        assertEquals("attempts_exhausted", row.failReason);
        assertEquals("provider_unavailable", row.lastErrorCode);
        assertNull(row.nextAttemptAt);
        assertEquals(6, deliveries.attempts().size());
        assertEquals("retryable_error", deliveries.attempts().get(5).outcome());
        List<Published> events = transaction.published("delivery.failed");
        assertEquals(1, events.size());
        assertEquals(List.of("deliveryId", "orderId", "deliveryType", "reason", "attempts"), List.copyOf(events.get(0).data().keySet()));
        assertEquals("attempts_exhausted", events.get(0).data().get("reason"));
        assertEquals(6, events.get(0).data().get("attempts"));
        assertEquals(ORDER.toString(), events.get(0).aggregateId());
        assertEquals(1.0, meters.counter("delivery_failed_total", "reason", "attempts_exhausted").count());
    }

    @Tag("TC-604")
    @Test
    void aPermanentErrorFailsTheDeliveryAtOnceWithoutRetries() {
        QueuedDelivery delivery = claim();

        assertTrue(outcomes.recordAttempt(delivery, new AttemptResult.Permanent("address_rejected"), T0));

        Row row = deliveries.row(ID);
        assertEquals("failed", row.status);
        assertEquals(1, row.attemptNo);
        assertEquals("address_rejected", row.failReason);
        assertEquals("address_rejected", row.lastErrorCode);
        assertEquals("permanent_error", deliveries.attempts().get(0).outcome());
        List<Published> events = transaction.published("delivery.failed");
        assertEquals(1, events.size());
        assertEquals("address_rejected", events.get(0).data().get("reason"));
        assertEquals(1, events.get(0).data().get("attempts"));
        assertEquals(1.0, meters.counter("delivery_failed_total", "reason", "address_rejected").count());
    }

    @Tag("TC-606")
    @Test
    void anOutcomeOfAnAttemptThatAnotherExecutorAlreadyRecordedChangesNothing() {
        QueuedDelivery delivery = claim();
        assertTrue(outcomes.recordAttempt(delivery, new AttemptResult.Accepted(), T0));
        transaction.published().clear();

        assertFalse(outcomes.recordAttempt(delivery, new AttemptResult.Accepted(), T0), "второй исход той же попытки");
        assertFalse(outcomes.recordAttempt(delivery, new AttemptResult.Retryable("provider_timeout"), T0));
        assertFalse(outcomes.recordAttempt(delivery, new AttemptResult.Permanent("address_rejected"), T0));

        Row row = deliveries.row(ID);
        assertEquals("sent", row.status);
        assertEquals(1, row.attemptNo);
        assertEquals(1, deliveries.attempts().size());
        assertEquals(0, transaction.published().size(), "поздний исход события не публикует");
        assertEquals(3.0, meters.counter("delivery_outcomes_stale_total").count());
    }

    @Tag("TC-606")
    @Test
    void aSecondExecutorWithAStaleCopyDoesNotSpendAnotherAttempt() {
        QueuedDelivery stale = claim();
        outcomes.recordAttempt(stale, new AttemptResult.Retryable("provider_unavailable"), T0);

        assertFalse(outcomes.recordAttempt(stale, new AttemptResult.Retryable("provider_unavailable"), T0));

        assertEquals(1, deliveries.row(ID).attemptNo, "вторая запись той же попытки не тратит попытку");
        assertEquals(1, deliveries.attempts().size());
    }
}
