package dgm.payment.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.payment.payments.FakePayments;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.service.PaymentOutcomes.Application;
import dgm.payment.payments.service.PaymentOutcomes.Result;
import dgm.payment.payments.service.PaymentOutcomes.Target;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Общий код применения исхода: статус, запись обработанного уведомления и событие Outbox меняются вместе или не меняются. */
@Tag("TC-502")
@Tag("TC-503")
@Tag("TC-504")
@Tag("TC-505")
class PaymentOutcomesTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PAYMENT = UUID.fromString("0199e0a0-0000-7000-8000-000000000401");
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-10-18T09:59:30.250Z");

    private FakePayments fake;
    private SimpleMeterRegistry meters;
    private PaymentOutcomes outcomes;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        meters = new SimpleMeterRegistry();
        outcomes = new PaymentOutcomes(fake.payments, fake.events, fake.transaction, new ManualClock(NOW), meters);
    }

    private Payment payment(String status, String gatewayId) {
        return fake.payments.putCreated(new Payment(PAYMENT, ORDER, gatewayId, 299800, "RUB", "https://x/pay", NOW.plusSeconds(60), status), NOW.minusSeconds(30));
    }

    private static Application paid(String gatewayId) {
        return new Application(gatewayId, "payment.paid", "evt_1", Target.CONFIRM, ORDER, OCCURRED, null);
    }

    private static Application declined(String gatewayId, String reason) {
        return new Application(gatewayId, "payment.declined", "evt_2", Target.REJECT, ORDER, OCCURRED, reason);
    }

    private double anomalies(String reason) {
        var counter = meters.find("payment_anomalies_total").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aPaidNotificationConfirmsThePaymentAndWritesTheEventInTheSameTransaction() {
        payment(Payment.CREATED, "pay_1");

        assertEquals(Result.APPLIED, outcomes.apply(paid("pay_1")));

        assertEquals(Payment.CONFIRMED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(OCCURRED, fake.payments.decidedAt.get(ORDER));
        assertEquals(PaymentEventRepository.APPLIED, fake.events.row("pay_1", "payment.paid").outcome());
        assertEquals(1, fake.transaction.committed);
        assertEquals(1, fake.transaction.events.size());
        FakePayments.Tx.Event event = fake.transaction.events.get(0);
        assertEquals("payment.events", event.topic());
        assertEquals("Payment", event.aggregateType());
        assertEquals(ORDER.toString(), event.aggregateId());
        assertEquals("payment.confirmed", event.type());
        assertEquals(1, event.schemaVersion());
        assertEquals(PAYMENT.toString(), event.data().get("paymentId"));
        assertEquals(ORDER.toString(), event.data().get("orderId"));
        assertEquals(Map.of("amount", 299800L, "currency", "RUB"), event.data().get("amount"));
        assertEquals("2026-10-18T09:59:30.250Z", event.data().get("confirmedAt"));
    }

    @Test
    void aDeclinedNotificationRejectsThePaymentWithTheReasonFromTheContract() {
        payment(Payment.CREATED, "pay_1");

        assertEquals(Result.APPLIED, outcomes.apply(declined("pay_1", "insufficient_funds")));

        assertEquals(Payment.REJECTED, fake.payments.byOrder.get(ORDER).status());
        FakePayments.Tx.Event event = fake.transaction.events.get(0);
        assertEquals("payment.rejected", event.type());
        assertEquals("insufficient_funds", event.data().get("reason"));
        assertFalse(event.data().containsKey("amount"));
    }

    @Test
    void aDeclineWithoutAReasonIsPublishedAsOther() {
        payment(Payment.CREATED, "pay_1");
        outcomes.apply(declined("pay_1", null));
        assertEquals("other", fake.transaction.events.get(0).data().get("reason"));
    }

    @Test
    void theSameNotificationTwiceChangesNothingTheSecondTime() {
        payment(Payment.CREATED, "pay_1");
        outcomes.apply(paid("pay_1"));

        assertEquals(Result.DUPLICATE, outcomes.apply(paid("pay_1")));

        assertEquals(1, fake.transaction.events.size());
        assertEquals(1, fake.events.rows.size());
        assertEquals(0, anomalies("status-confirmed"));
    }

    @Test
    void theSameOutcomeReachedByAnotherPathIsADuplicateNotAnAnomaly() {
        payment(Payment.CONFIRMED, "pay_1");

        assertEquals(Result.DUPLICATE, outcomes.apply(new Application("pay_1", "reconcile.paid", "reconciliation", Target.CONFIRM, ORDER, OCCURRED, null)));

        assertTrue(fake.events.rows.isEmpty(), "запись о повторе не нужна: статус уже тот");
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Test
    void aPaidNotificationForARefundedPaymentIsADuplicate() {
        payment(Payment.REFUNDED, "pay_1");
        assertEquals(Result.DUPLICATE, outcomes.apply(paid("pay_1")));
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Test
    void aDeclineAfterAConfirmationIsAnAnomalyAndTheStatusStays() {
        payment(Payment.CONFIRMED, "pay_1");

        assertEquals(Result.ANOMALY, outcomes.apply(declined("pay_1", "declined")));

        assertEquals(Payment.CONFIRMED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(PaymentEventRepository.ANOMALY, fake.events.row("pay_1", "payment.declined").outcome());
        assertTrue(fake.transaction.events.isEmpty());
        assertEquals(1, anomalies("status-confirmed"));
    }

    @Test
    void aPaidNotificationAfterARejectionIsAnAnomaly() {
        payment(Payment.REJECTED, "pay_1");

        assertEquals(Result.ANOMALY, outcomes.apply(paid("pay_1")));

        assertEquals(Payment.REJECTED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(1, anomalies("status-rejected"));
    }

    @Test
    void aRepeatedAnomalyIsNotCountedTwice() {
        payment(Payment.CONFIRMED, "pay_1");
        outcomes.apply(declined("pay_1", "declined"));

        assertEquals(Result.DUPLICATE, outcomes.apply(declined("pay_1", "declined")));

        assertEquals(1, anomalies("status-confirmed"));
        assertEquals(1, fake.events.rows.size());
    }

    @Test
    void aNotificationWithAnotherGatewayPaymentIsAnAnomalyAndNothingChanges() {
        payment(Payment.CREATED, "pay_1");

        assertEquals(Result.ANOMALY, outcomes.apply(paid("pay_other")));

        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(PaymentEventRepository.ANOMALY, fake.events.row("pay_other", "payment.paid").outcome());
        assertEquals(1, anomalies("gateway-id-mismatch"));
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Test
    void aPaymentWithoutAGatewayIdCannotBeConfirmedByNotification() {
        payment(Payment.CREATED, null);
        assertEquals(Result.ANOMALY, outcomes.apply(paid("pay_1")));
        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
    }

    @Test
    void anUnknownOrderIsAnError() {
        assertThrows(IllegalStateException.class, () -> outcomes.apply(paid("pay_1")));
        assertEquals(1, fake.transaction.rolledBack);
    }

    @Test
    void recordingAnUnappliedNotificationWritesOnceAndCountsOnce() {
        Payment p = payment(Payment.CREATED, "pay_1");

        assertTrue(outcomes.recordUnapplied(p, "pay_1", "payment.paid", "evt_1", OCCURRED, PaymentEventRepository.AMOUNT_MISMATCH, "amount-mismatch"));
        assertFalse(outcomes.recordUnapplied(p, "pay_1", "payment.paid", "evt_1", OCCURRED, PaymentEventRepository.AMOUNT_MISMATCH, "amount-mismatch"));

        assertEquals(PaymentEventRepository.AMOUNT_MISMATCH, fake.events.row("pay_1", "payment.paid").outcome());
        assertEquals(1, anomalies("amount-mismatch"));
        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertNull(fake.events.row("pay_1", "payment.declined"));
    }
}
