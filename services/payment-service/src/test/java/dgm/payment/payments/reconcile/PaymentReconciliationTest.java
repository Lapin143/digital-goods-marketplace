package dgm.payment.payments.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.payment.payments.FakePayments;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.gateway.GatewayStatus;
import dgm.payment.payments.reconcile.PaymentReconciliation.Result;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.service.PaymentOutcomes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Сверка одного платежа со шлюзом: лечит потерянное уведомление тем же путём, что вебхук, и ничего не портит при повторе. */
@Tag("TC-508")
class PaymentReconciliationTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PAYMENT = UUID.fromString("0199e0a0-0000-7000-8000-000000000401");
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final long AMOUNT = 299800;

    private FakePayments fake;
    private SimpleMeterRegistry meters;
    private PaymentReconciliation reconciliation;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        meters = new SimpleMeterRegistry();
        ManualClock clock = new ManualClock(NOW);
        PaymentOutcomes outcomes = new PaymentOutcomes(fake.payments, fake.events, fake.transaction, clock, meters);
        reconciliation = new PaymentReconciliation(fake.gateway, outcomes, clock, meters);
    }

    private Payment payment(String status, String gatewayId) {
        return fake.payments.putCreated(new Payment(PAYMENT, ORDER, gatewayId, AMOUNT, "RUB", "https://x/pay", NOW.plusSeconds(60), status),
                NOW.minusSeconds(600));
    }

    private double count(String result) {
        var counter = meters.find("payment_reconciled_total").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private double anomalies(String reason) {
        var counter = meters.find("payment_anomalies_total").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aPaymentPaidAtTheGatewayIsConfirmedAndTheEventIsWrittenOnce() {
        Payment p = payment(Payment.CREATED, "pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");

        assertEquals(Result.CONFIRMED, reconciliation.reconcile(p));

        assertEquals(Payment.CONFIRMED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(PaymentEventRepository.APPLIED, fake.events.row("pay_1", "reconcile.paid").outcome());
        assertEquals(1, fake.transaction.events.size());
        assertEquals("payment.confirmed", fake.transaction.events.get(0).type());
        assertEquals(NOW, fake.payments.decidedAt.get(ORDER));
        assertEquals(1, fake.gateway.retryingCalls, "сверка идёт не в цепочке вебхука и повторяет запрос при сбое");
        assertEquals(0, fake.gateway.singleAttemptCalls);
        assertEquals(1, count("confirmed"));
    }

    @Test
    void aPaymentDeclinedAtTheGatewayIsRejected() {
        Payment p = payment(Payment.CREATED, "pay_1");
        fake.gateway.know("pay_1", GatewayStatus.DECLINED, AMOUNT, "RUB");

        assertEquals(Result.REJECTED, reconciliation.reconcile(p));

        assertEquals(Payment.REJECTED, fake.payments.byOrder.get(ORDER).status());
        assertEquals("payment.rejected", fake.transaction.events.get(0).type());
        assertEquals("declined", fake.transaction.events.get(0).data().get("reason"));
    }

    @Test
    void aPaymentStillWaitingOrExpiredAtTheGatewayStaysCreated() {
        Payment p = payment(Payment.CREATED, "pay_1");
        for (GatewayStatus status : new GatewayStatus[] {GatewayStatus.PENDING, GatewayStatus.EXPIRED}) {
            fake.gateway.know("pay_1", status, AMOUNT, "RUB");
            assertEquals(Result.UNCHANGED, reconciliation.reconcile(p), status.name());
        }
        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertTrue(fake.events.rows.isEmpty());
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Test
    void aWrongAmountAtTheGatewayStopsTheConfirmation() {
        Payment p = payment(Payment.CREATED, "pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT - 1, "RUB");

        assertEquals(Result.ANOMALY, reconciliation.reconcile(p));

        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(PaymentEventRepository.AMOUNT_MISMATCH, fake.events.row("pay_1", "reconcile.paid").outcome());
        assertEquals(1, anomalies("reconcile-amount-mismatch"));
        assertEquals(1, count("anomaly"));
    }

    @Test
    void aRefundStatusOfACreatedPaymentIsAnAnomaly() {
        Payment p = payment(Payment.CREATED, "pay_1");
        for (GatewayStatus status : new GatewayStatus[] {GatewayStatus.REFUNDING, GatewayStatus.REFUNDED, GatewayStatus.UNKNOWN}) {
            fake.gateway.know("pay_1", status, AMOUNT, "RUB");
            assertEquals(Result.ANOMALY, reconciliation.reconcile(p), status.name());
        }
        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(1, anomalies("reconcile-status-refunding"));
        assertEquals(PaymentEventRepository.ANOMALY, fake.events.row("pay_1", "reconcile.anomaly").outcome());
    }

    @Test
    void aPaymentUnknownToTheGatewayIsAnAnomaly() {
        Payment p = payment(Payment.CREATED, "pay_1");

        assertEquals(Result.ANOMALY, reconciliation.reconcile(p));

        assertEquals(1, anomalies("reconcile-gateway-unknown"));
        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
    }

    @Test
    void whenTheGatewayDoesNotAnswerNothingChangesAndTheNextPassTriesAgain() {
        Payment p = payment(Payment.CREATED, "pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");
        fake.gateway.statusFailures = 1;

        assertEquals(Result.UNAVAILABLE, reconciliation.reconcile(p));
        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertTrue(fake.events.rows.isEmpty());

        assertEquals(Result.CONFIRMED, reconciliation.reconcile(p));
        assertEquals(1, count("unavailable"));
    }

    @Test
    void paymentsThatAreNotOpenOrHaveNoGatewayIdAreNotSentToTheGateway() {
        assertEquals(Result.UNCHANGED, reconciliation.reconcile(payment(Payment.CONFIRMED, "pay_1")));
        assertEquals(Result.UNCHANGED, reconciliation.reconcile(payment(Payment.CREATED, null)));
        assertTrue(fake.gateway.statusCalls.isEmpty());
    }

    @Test
    void aSecondReconciliationOfTheSamePaymentChangesNothing() {
        Payment p = payment(Payment.CREATED, "pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");
        reconciliation.reconcile(p);

        // Тот же устаревший объект «создан»: статус уже сменён, общий код видит повтор и ничего не пишет
        assertEquals(Result.UNCHANGED, reconciliation.reconcile(p));

        assertEquals(1, fake.transaction.events.size());
        assertEquals(1, fake.events.rows.size());
    }

    @Test
    void anOutcomeAlreadyAppliedByTheWebhookIsNotAnAnomaly() {
        Payment p = payment(Payment.CREATED, "pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");
        fake.payments.put(new Payment(PAYMENT, ORDER, "pay_1", AMOUNT, "RUB", "https://x/pay", NOW.plusSeconds(60), Payment.CONFIRMED));

        assertEquals(Result.UNCHANGED, reconciliation.reconcile(p));

        assertTrue(fake.transaction.events.isEmpty());
        assertEquals(0, anomalies("status-confirmed"));
    }

    @Test
    void aDeclineAtTheGatewayForAPaymentAlreadyConfirmedHereIsAnAnomaly() {
        Payment p = payment(Payment.CREATED, "pay_1");
        fake.gateway.know("pay_1", GatewayStatus.DECLINED, AMOUNT, "RUB");
        fake.payments.put(new Payment(PAYMENT, ORDER, "pay_1", AMOUNT, "RUB", "https://x/pay", NOW.plusSeconds(60), Payment.CONFIRMED));

        assertEquals(Result.ANOMALY, reconciliation.reconcile(p));

        assertEquals(Payment.CONFIRMED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(1, anomalies("status-confirmed"));
    }
}
