package dgm.payment.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.kit.time.Timestamps;
import dgm.payment.payments.FakePayments;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.domain.RefundAttempt;
import dgm.payment.payments.domain.RefundPayment;
import dgm.payment.payments.domain.RefundSchedule;
import dgm.payment.payments.gateway.GatewayRefund;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.service.RefundOutcomes.Confirmation;
import dgm.payment.payments.service.RefundOutcomes.Confirmed;
import dgm.payment.payments.service.RefundOutcomes.Failure;
import dgm.payment.payments.service.RefundOutcomes.Request;
import dgm.payment.payments.service.RefundOutcomes.Requested;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Порядок возврата на подмене базы и Outbox (SM-02/T4, INV-15): запрос, итог попытки, повторы по расписанию, эскалация и подтверждение
 * шлюзом. Статус платежа, попытки и события Outbox меняются вместе.
 */
@Tag("TC-521")
@Tag("TC-522")
@Tag("TC-523")
@Tag("TC-524")
class RefundOutcomesTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PAYMENT = UUID.fromString("0199e0a0-0000-7000-8000-000000000401");
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-10-18T10:00:20.250Z");
    private static final long AMOUNT = 299800;

    private FakePayments fake;
    private SimpleMeterRegistry meters;
    private ManualClock clock;
    private RefundOutcomes outcomes;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        meters = new SimpleMeterRegistry();
        clock = new ManualClock(NOW);
        outcomes = new RefundOutcomes(fake.refunds, fake.events, fake.transaction, RefundSchedule.parse("0s,30s,2m,5m,10m", 0, new Random(3)), clock, meters);
        fake.confirmedPayment(PAYMENT, ORDER, "pay_1", AMOUNT);
    }

    private static Request request() {
        return new Request(ORDER, AMOUNT, "RUB", "late_payment");
    }

    private RefundAttempt claimOne() {
        List<RefundAttempt> claimed = fake.refunds.claimDue(clock.instant(), Duration.ofMinutes(2), 10);
        assertEquals(1, claimed.size(), "к выполнению должна наступить одна попытка");
        return claimed.get(0);
    }

    private double count(String metric, String tag, String value) {
        var counter = meters.find(metric).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    private double refunds(String result) {
        return count("payment_refunds_total", "result", result);
    }

    private Confirmation confirmation(String eventType, String gatewayPaymentId, String refundId) {
        return new Confirmation(gatewayPaymentId, eventType, "evt_9", ORDER, refundId, OCCURRED);
    }

    @Test
    void theCommandRecordsTheReasonAndSchedulesTheFirstAttemptAtOnce() {
        assertEquals(Requested.STARTED, outcomes.request(request()));

        RefundPayment payment = fake.refunds.payment(PAYMENT);
        assertEquals("late_payment", payment.refundReason());
        assertEquals(Payment.CONFIRMED, payment.status(), "платёж станет возвращённым только после денег");
        List<RefundAttempt> attempts = fake.refunds.attemptsOf(PAYMENT);
        assertEquals(1, attempts.size());
        assertEquals(1, attempts.get(0).attemptNo());
        assertEquals(RefundAttempt.PENDING, attempts.get(0).status());
        assertEquals(NOW, attempts.get(0).nextAttemptAt(), "первая попытка без паузы");
        assertEquals(1, refunds("requested"));
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Test
    void aRepeatedCommandDoesNotCreateASecondRefund() {
        outcomes.request(request());

        assertEquals(Requested.IN_PROGRESS, outcomes.request(request()));
        assertEquals(Requested.IN_PROGRESS, outcomes.request(new Request(ORDER, AMOUNT, "RUB", "dispute_decision")));

        assertEquals(1, fake.refunds.attemptsOf(PAYMENT).size());
        assertEquals("late_payment", fake.refunds.payment(PAYMENT).refundReason(), "причина первой команды не затирается");
        assertEquals(2, refunds("command-in-progress"));
    }

    @Test
    void aCommandForARefundedPaymentOrOneInTheAdminQueueDoesNothing() {
        outcomes.request(request());
        RefundAttempt attempt = claimOne();
        outcomes.accepted(attempt, new GatewayRefund("ref_1", true));

        assertEquals(Requested.ALREADY_REFUNDED, outcomes.request(request()));
        assertEquals(1, fake.refunds.attemptsOf(PAYMENT).size());

        UUID order2 = UUID.fromString("0199e0a0-0000-7000-8000-000000000302");
        UUID payment2 = UUID.fromString("0199e0a0-0000-7000-8000-000000000402");
        fake.refunds.put(new RefundPayment(payment2, order2, "pay_2", AMOUNT, "RUB", Payment.CONFIRMED, "late_payment", null, true, NOW, null));
        assertEquals(Requested.IN_QUEUE, outcomes.request(new Request(order2, AMOUNT, "RUB", "late_payment")));
        assertTrue(fake.refunds.attemptsOf(payment2).isEmpty());
    }

    @Test
    void aCommandNotForOurDataIsRefusedWithoutChanges() {
        UUID unknown = UUID.fromString("0199e0a0-0000-7000-8000-000000000999");
        assertEquals(Requested.NO_PAYMENT, outcomes.request(new Request(unknown, AMOUNT, "RUB", "late_payment")));

        UUID order2 = UUID.fromString("0199e0a0-0000-7000-8000-000000000302");
        fake.refunds.put(new RefundPayment(UUID.fromString("0199e0a0-0000-7000-8000-000000000402"), order2, "pay_2", AMOUNT, "RUB", Payment.CREATED, null, null,
                false, null, null));
        assertEquals(Requested.NOT_PAID, outcomes.request(new Request(order2, AMOUNT, "RUB", "late_payment")));

        assertEquals(Requested.AMOUNT_MISMATCH, outcomes.request(new Request(ORDER, AMOUNT - 100, "RUB", "late_payment")));
        assertEquals(Requested.AMOUNT_MISMATCH, outcomes.request(new Request(ORDER, AMOUNT, "USD", "late_payment")));

        assertNull(fake.refunds.payment(PAYMENT).refundReason(), "возврат неверной суммой причину не записывает");
        assertTrue(fake.refunds.attempts.isEmpty());
        assertEquals(1, refunds("command-no-payment"));
        assertEquals(1, refunds("command-not-paid"));
        assertEquals(2, refunds("command-amount-mismatch"));
    }

    @Test
    void anInstantRefundMakesThePaymentRefundedAndWritesOneEventInTheSameTransaction() {
        outcomes.request(request());
        RefundAttempt attempt = claimOne();
        clock.advance(Duration.ofSeconds(2));

        outcomes.accepted(attempt, new GatewayRefund("ref_1", true));

        RefundPayment payment = fake.refunds.payment(PAYMENT);
        assertEquals(Payment.REFUNDED, payment.status());
        assertEquals("ref_1", payment.gatewayRefundId());
        assertEquals(NOW.plusSeconds(2), payment.refundedAt());
        assertEquals(RefundAttempt.SUCCEEDED, fake.refunds.attemptsOf(PAYMENT).get(0).status());
        assertEquals(1, fake.transaction.events.size());
        FakePayments.Tx.Event event = fake.transaction.events.get(0);
        assertEquals("payment.events", event.topic());
        assertEquals("payment.refunded", event.type());
        assertEquals(ORDER.toString(), event.aggregateId());
        assertEquals(PAYMENT.toString(), event.data().get("paymentId"));
        assertEquals(ORDER.toString(), event.data().get("orderId"));
        assertEquals(Map.of("amount", AMOUNT, "currency", "RUB"), event.data().get("amount"));
        assertEquals("late_payment", event.data().get("refundReason"));
        assertEquals(false, event.data().get("manual"));
        assertEquals(Timestamps.format(NOW.plusSeconds(2)), event.data().get("refundedAt"));
        assertEquals(1, refunds("completed"));
    }

    @Test
    void aRefundInProcessingWaitsForTheConfirmationAndKeepsTheRefundId() {
        outcomes.request(request());
        RefundAttempt attempt = claimOne();

        outcomes.accepted(attempt, new GatewayRefund("ref_1", false));

        RefundPayment payment = fake.refunds.payment(PAYMENT);
        assertEquals(Payment.CONFIRMED, payment.status());
        assertEquals("ref_1", payment.gatewayRefundId());
        assertEquals(RefundAttempt.PROCESSING, fake.refunds.attemptsOf(PAYMENT).get(0).status());
        assertTrue(fake.transaction.events.isEmpty(), "события до подтверждения денег нет");
        assertEquals(1, refunds("processing"));
    }

    @Test
    void aResultThatArrivesAfterTheConfirmationChangesNothing() {
        outcomes.request(request());
        RefundAttempt attempt = claimOne();
        assertEquals(Confirmed.APPLIED, outcomes.confirmed(confirmation("refund.completed", "pay_1", "ref_1")));
        int events = fake.transaction.events.size();

        outcomes.accepted(attempt, new GatewayRefund("ref_1", true));

        assertEquals(events, fake.transaction.events.size());
        assertEquals(Payment.REFUNDED, fake.refunds.payment(PAYMENT).status());
    }

    @Test
    void aFailedAttemptSchedulesTheNextOneByTheSchedule() {
        outcomes.request(request());
        RefundAttempt first = claimOne();

        assertEquals(Failure.RETRY, outcomes.failed(first, "gateway_unavailable"));

        List<RefundAttempt> attempts = fake.refunds.attemptsOf(PAYMENT);
        assertEquals(2, attempts.size());
        assertEquals(RefundAttempt.FAILED, attempts.get(0).status());
        assertEquals("gateway_unavailable", attempts.get(0).errorCode());
        assertNull(attempts.get(0).nextAttemptAt());
        assertEquals(2, attempts.get(1).attemptNo());
        assertEquals(RefundAttempt.PENDING, attempts.get(1).status());
        assertEquals(NOW.plusSeconds(30), attempts.get(1).nextAttemptAt());
        assertTrue(fake.transaction.events.isEmpty());
        assertEquals(1, refunds("failed"));
    }

    @Test
    void theFifthFailureHandsTheRefundToTheAdministratorWithOneEvent() {
        outcomes.request(request());
        for (int attemptNo = 1; attemptNo <= 5; attemptNo++) {
            clock.advance(Duration.ofMinutes(11));
            RefundAttempt attempt = claimOne();
            assertEquals(attemptNo, attempt.attemptNo());
            assertEquals(attemptNo < 5 ? Failure.RETRY : Failure.ESCALATED, outcomes.failed(attempt, "gateway_rejected_422"), "попытка " + attemptNo);
        }

        RefundPayment payment = fake.refunds.payment(PAYMENT);
        assertTrue(payment.inAdminQueue());
        assertEquals(clock.instant(), payment.escalatedAt());
        assertEquals(Payment.CONFIRMED, payment.status(), "деньги не возвращены, платёж остаётся подтверждённым");
        assertEquals(5, fake.refunds.attemptsOf(PAYMENT).size());
        assertTrue(fake.refunds.attemptsOf(PAYMENT).stream().noneMatch(RefundAttempt::open), "после эскалации открытых попыток нет");
        assertEquals(1, fake.transaction.events.size());
        FakePayments.Tx.Event event = fake.transaction.events.get(0);
        assertEquals("payment.refund-escalated", event.type());
        assertEquals("payment.events", event.topic());
        assertEquals(5, event.data().get("attempts"));
        assertEquals(Map.of("amount", AMOUNT, "currency", "RUB"), event.data().get("amount"));
        assertEquals(PAYMENT.toString(), event.data().get("paymentId"));
        assertEquals(1, refunds("escalated"));
        assertEquals(4, refunds("failed"));
        assertEquals(Requested.IN_QUEUE, outcomes.request(request()), "команда после эскалации новых попыток не создаёт");
        assertEquals(5, fake.refunds.attemptsOf(PAYMENT).size());
    }

    @Test
    void aFailureOfAnAttemptClosedByAnotherPathChangesNothing() {
        outcomes.request(request());
        RefundAttempt attempt = claimOne();
        assertEquals(Failure.RETRY, outcomes.failed(attempt, "gateway_unavailable"));
        int attempts = fake.refunds.attemptsOf(PAYMENT).size();

        assertEquals(Failure.SUPERSEDED, outcomes.failed(attempt, "gateway_unavailable"), "одну попытку закрывают один раз");
        assertEquals(attempts, fake.refunds.attemptsOf(PAYMENT).size());

        clock.advance(Duration.ofMinutes(1));
        RefundAttempt second = claimOne();
        outcomes.confirmed(confirmation("refund.completed", "pay_1", null));
        assertEquals(Failure.SUPERSEDED, outcomes.failed(second, "gateway_unavailable"), "платёж уже возвращён");
        assertEquals(2, refunds("superseded"));
    }

    @Test
    void theConfirmationOfTheGatewayCompletesTheRefundOnceAndClosesTheAttempts() {
        outcomes.request(request());
        outcomes.accepted(claimOne(), new GatewayRefund("ref_1", false));

        assertEquals(Confirmed.APPLIED, outcomes.confirmed(confirmation("refund.completed", "pay_1", "ref_1")));

        RefundPayment payment = fake.refunds.payment(PAYMENT);
        assertEquals(Payment.REFUNDED, payment.status());
        assertEquals(OCCURRED, payment.refundedAt());
        assertEquals(RefundAttempt.SUCCEEDED, fake.refunds.attemptsOf(PAYMENT).get(0).status());
        assertEquals(PaymentEventRepository.APPLIED, fake.events.row("pay_1", "refund.completed").outcome());
        assertEquals(1, fake.transaction.events.size());
        assertEquals("payment.refunded", fake.transaction.events.get(0).type());
        assertEquals(false, fake.transaction.events.get(0).data().get("manual"));
        assertEquals(Timestamps.format(OCCURRED), fake.transaction.events.get(0).data().get("refundedAt"));

        assertEquals(Confirmed.DUPLICATE, outcomes.confirmed(confirmation("refund.completed", "pay_1", "ref_1")));
        assertEquals(1, fake.transaction.events.size(), "повтор второго события не рождает");
        assertEquals(1, refunds("confirmed"));
        assertEquals(1, refunds("confirmed-duplicate"));
    }

    @Test
    void aRefundTheAdministratorAlreadyMarkedIsADuplicateNotAnAnomaly() {
        outcomes.request(request());
        for (int i = 1; i <= 5; i++) {
            clock.advance(Duration.ofMinutes(11));
            outcomes.failed(claimOne(), "gateway_unavailable");
        }
        fake.transaction.execute(() -> outcomes.markRefunded(fake.refunds.payment(PAYMENT), "manual-77", clock.instant(), true));
        assertEquals(Payment.REFUNDED, fake.refunds.payment(PAYMENT).status());

        assertEquals(Confirmed.DUPLICATE, outcomes.confirmed(confirmation("refund.completed", "pay_1", "ref_1")));

        assertEquals(0, count("payment_anomalies_total", "reason", "status-refunded"));
        assertFalse(fake.events.rows.containsKey("pay_1|refund.completed"));
    }

    @Test
    void aConfirmationOfARefundNobodyRequestedIsAnAnomalyAndChangesNothing() {
        assertEquals(Confirmed.ANOMALY, outcomes.confirmed(confirmation("refund.completed", "pay_1", "ref_1")));

        assertEquals(Payment.CONFIRMED, fake.refunds.payment(PAYMENT).status());
        assertEquals(PaymentEventRepository.ANOMALY, fake.events.row("pay_1", "refund.completed").outcome());
        assertTrue(fake.transaction.events.isEmpty());
        assertEquals(1, count("payment_anomalies_total", "reason", "refund-not-requested"));
        assertEquals(1, refunds("confirmed-anomaly"));

        assertEquals(Confirmed.DUPLICATE, outcomes.confirmed(confirmation("refund.completed", "pay_1", "ref_1")), "то же уведомление не считается дважды");
        assertEquals(1, count("payment_anomalies_total", "reason", "refund-not-requested"));
    }

    @Test
    void aConfirmationNamingAnotherGatewayPaymentOrRefundIsAnAnomaly() {
        outcomes.request(request());
        outcomes.accepted(claimOne(), new GatewayRefund("ref_1", false));

        assertEquals(Confirmed.ANOMALY, outcomes.confirmed(confirmation("refund.completed", "pay_other", "ref_1")));
        assertEquals(1, count("payment_anomalies_total", "reason", "gateway-id-mismatch"));

        assertEquals(Confirmed.ANOMALY, outcomes.confirmed(confirmation("refund.completed", "pay_1", "ref_other")));
        assertEquals(1, count("payment_anomalies_total", "reason", "gateway-refund-mismatch"));

        assertEquals(Payment.CONFIRMED, fake.refunds.payment(PAYMENT).status());
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Test
    void aConfirmationForAnUnknownOrderOrAnUnpaidPaymentIsAnAnomaly() {
        UUID unknown = UUID.fromString("0199e0a0-0000-7000-8000-000000000999");
        assertEquals(Confirmed.ANOMALY, outcomes.confirmed(new Confirmation("pay_1", "refund.completed", "evt_1", unknown, null, OCCURRED)));
        assertEquals(1, count("payment_anomalies_total", "reason", "refund-unknown-payment"));

        UUID order2 = UUID.fromString("0199e0a0-0000-7000-8000-000000000302");
        fake.refunds.put(new RefundPayment(UUID.fromString("0199e0a0-0000-7000-8000-000000000402"), order2, "pay_2", AMOUNT, "RUB", Payment.CREATED, null, null,
                false, null, null));
        assertEquals(Confirmed.ANOMALY, outcomes.confirmed(new Confirmation("pay_2", "refund.completed", "evt_2", order2, null, OCCURRED)));
        assertEquals(1, count("payment_anomalies_total", "reason", "status-created"));
    }

    @Test
    void aPaymentWithoutAReasonCannotBecomeRefunded() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> outcomes.markRefunded(fake.refunds.payment(PAYMENT), "ref_1", NOW, false));
        assertTrue(e.getMessage().contains(PAYMENT.toString()), e.getMessage());
        assertEquals(Payment.CONFIRMED, fake.refunds.payment(PAYMENT).status());
    }
}
