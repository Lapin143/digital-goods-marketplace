package dgm.payment.payments.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.payment.payments.FakePayments;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.domain.RefundAttempt;
import dgm.payment.payments.domain.RefundSchedule;
import dgm.payment.payments.gateway.GatewayRefund;
import dgm.payment.payments.gateway.GatewayStatus;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.service.PaymentOutcomes;
import dgm.payment.payments.service.RefundOutcomes;
import dgm.payment.payments.webhook.PaymentWebhookService.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Шаги 3 – 7 цепочки вебхука (ADR-015): сопоставление, сумма, дедупликация, запрос статуса и применение исхода. */
class PaymentWebhookServiceTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PAYMENT = UUID.fromString("0199e0a0-0000-7000-8000-000000000401");
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-10-18T09:59:30Z");
    private static final long AMOUNT = 299800;

    private FakePayments fake;
    private SimpleMeterRegistry meters;
    private PaymentWebhookService service;
    private RefundOutcomes refundOutcomes;
    private ManualClock clock;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        meters = new SimpleMeterRegistry();
        clock = new ManualClock(NOW);
        PaymentOutcomes outcomes = new PaymentOutcomes(fake.payments, fake.events, fake.transaction, clock, meters);
        refundOutcomes = new RefundOutcomes(fake.refunds, fake.events, fake.transaction, RefundSchedule.parse("0s,30s,2m,5m,10m", 0, new Random(1)), clock, meters);
        service = new PaymentWebhookService(fake.payments, fake.events, fake.unmatched, fake.gateway, outcomes, refundOutcomes, clock, meters);
    }

    private Payment payment(String gatewayId) {
        return fake.payments.putCreated(new Payment(PAYMENT, ORDER, gatewayId, AMOUNT, "RUB", gatewayId == null ? null : "https://x/pay", NOW.plusSeconds(60),
                Payment.CREATED), NOW.minusSeconds(30));
    }

    private static GatewayNotification notification(String type, String paymentId, UUID order, long amount, String currency) {
        return new GatewayNotification("evt_" + type, type, OCCURRED, paymentId, order, amount, currency, null, null);
    }

    private static GatewayNotification paid(String paymentId) {
        return notification("payment.paid", paymentId, ORDER, AMOUNT, "RUB");
    }

    private double count(String result) {
        var counter = meters.find("payment_webhooks_total").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Tag("TC-502")
    @Test
    void aPaidNotificationConfirmsThePaymentAfterAskingTheGatewayOnce() {
        payment("pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");

        assertEquals(Result.APPLIED, service.receive(paid("pay_1")));

        assertEquals(Payment.CONFIRMED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(1, fake.gateway.singleAttemptCalls, "в цепочке вебхука одна попытка, повтор сделает шлюз (ADR-015)");
        assertEquals(0, fake.gateway.retryingCalls);
        assertEquals(1, fake.transaction.events.size());
        assertEquals(1, count("applied"));
    }

    @Tag("TC-503")
    @Test
    void aDeclinedNotificationRejectsThePaymentWithoutAskingTheGateway() {
        payment("pay_1");
        GatewayNotification n = new GatewayNotification("evt_d", "payment.declined", OCCURRED, "pay_1", ORDER, AMOUNT, "RUB", null, "declined");

        assertEquals(Result.APPLIED, service.receive(n));

        assertEquals(Payment.REJECTED, fake.payments.byOrder.get(ORDER).status());
        assertTrue(fake.gateway.statusCalls.isEmpty());
        assertEquals("declined", fake.transaction.events.get(0).data().get("reason"));
    }

    @Tag("TC-504")
    @Test
    void theSameNotificationAgainIsAnsweredWithoutTheGatewayAndWithoutChanges() {
        payment("pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");
        service.receive(paid("pay_1"));

        assertEquals(Result.DUPLICATE, service.receive(paid("pay_1")));

        assertEquals(1, fake.gateway.statusCalls.size());
        assertEquals(1, fake.transaction.events.size());
        assertEquals(1, count("duplicate"));
    }

    @Tag("TC-504")
    @Test
    void sessionExpiryAndUnknownTypesAreAcceptedAndIgnored() {
        payment("pay_1");
        for (String type : new String[] {"payment.session-expired", "payment.something-new"}) {
            assertEquals(Result.IGNORED, service.receive(notification(type, "pay_1", ORDER, AMOUNT, "RUB")), type);
        }
        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertTrue(fake.events.rows.isEmpty());
        assertTrue(fake.gateway.statusCalls.isEmpty());
        assertEquals(2, count("ignored"));
    }

    @Tag("TC-507")
    @Test
    void aNotificationForAnUnknownPaymentIsStoredForTheSchedulerOnce() {
        assertEquals(Result.UNMATCHED, service.receive(paid("pay_9")));
        assertEquals(Result.UNMATCHED, service.receive(paid("pay_9")));

        assertEquals(1, fake.unmatched.items.size());
        var stored = fake.unmatched.items.values().iterator().next().notification;
        assertEquals("pay_9", stored.gatewayPaymentId());
        assertEquals("payment.paid", stored.eventType());
        assertEquals(ORDER, stored.orderId());
        assertEquals(NOW, stored.receivedAt());
        assertEquals(2, count("unmatched"));
        assertTrue(fake.gateway.statusCalls.isEmpty());
    }

    @Tag("TC-507")
    @Test
    void aGatewayPaymentIdNotYetWrittenIsTakenFromTheNotification() {
        payment(null);
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");

        assertEquals(Result.APPLIED, service.receive(paid("pay_1")));

        Payment stored = fake.payments.byOrder.get(ORDER);
        assertEquals("pay_1", stored.gatewayPaymentId());
        assertEquals(Payment.CONFIRMED, stored.status());
        assertNull(stored.sessionUrl(), "адрес страницы оплаты допишет открытие сессии");
    }

    @Tag("TC-505")
    @Test
    void aNotificationWithAnotherGatewayPaymentForTheSameOrderIsAnAnomaly() {
        payment("pay_1");

        assertEquals(Result.ANOMALY, service.receive(paid("pay_2")));

        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(PaymentEventRepository.ANOMALY, fake.events.row("pay_2", "payment.paid").outcome());
        assertTrue(fake.gateway.statusCalls.isEmpty());
        assertEquals(1, count("anomaly"));
    }

    @Tag("TC-505")
    @Test
    void aNotificationNamingAnotherOrderForAKnownGatewayPaymentIsAnAnomaly() {
        payment("pay_1");
        UUID other = UUID.fromString("0199e0a0-0000-7000-8000-000000000999");

        assertEquals(Result.ANOMALY, service.receive(notification("payment.paid", "pay_1", other, AMOUNT, "RUB")));

        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
    }

    @Tag("TC-505")
    @Test
    void aWrongAmountLeavesTheStatusAndIsRecordedOnce() {
        payment("pay_1");
        GatewayNotification wrong = notification("payment.paid", "pay_1", ORDER, AMOUNT - 100, "RUB");

        assertEquals(Result.AMOUNT_MISMATCH, service.receive(wrong));
        assertEquals(Result.AMOUNT_MISMATCH, service.receive(wrong));

        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(PaymentEventRepository.AMOUNT_MISMATCH, fake.events.row("pay_1", "payment.paid").outcome());
        var counter = meters.find("payment_anomalies_total").tag("reason", "amount-mismatch").counter();
        assertEquals(1.0, counter.count(), "повтор того же уведомления метрику не увеличивает");
        assertTrue(fake.gateway.statusCalls.isEmpty());
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Tag("TC-505")
    @Test
    void aWrongCurrencyIsTreatedLikeAWrongAmount() {
        payment("pay_1");
        assertEquals(Result.AMOUNT_MISMATCH, service.receive(notification("payment.paid", "pay_1", ORDER, AMOUNT, "USD")));
        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
    }

    @Tag("TC-505")
    @Test
    void whenTheGatewayDoesNotConfirmThePaidStatusThePaymentStaysAndAnAnomalyIsRecorded() {
        payment("pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PENDING, AMOUNT, "RUB");

        assertEquals(Result.ANOMALY, service.receive(paid("pay_1")));

        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(PaymentEventRepository.ANOMALY, fake.events.row("pay_1", "payment.paid").outcome());
        assertEquals(1.0, meters.find("payment_anomalies_total").tag("reason", "gateway-status-mismatch").counter().count());
    }

    @Tag("TC-505")
    @Test
    void whenTheGatewayReportsAnotherAmountThePaymentStays() {
        payment("pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT + 1, "RUB");

        assertEquals(Result.ANOMALY, service.receive(paid("pay_1")));

        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(1.0, meters.find("payment_anomalies_total").tag("reason", "gateway-amount-mismatch").counter().count());
    }

    @Tag("TC-505")
    @Test
    void whenTheGatewayDoesNotKnowThePaymentItIsAnAnomaly() {
        payment("pay_1");

        assertEquals(Result.ANOMALY, service.receive(paid("pay_1")));

        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(1.0, meters.find("payment_anomalies_total").tag("reason", "gateway-unknown-payment").counter().count());
    }

    @Tag("TC-508")
    @Test
    void whenTheGatewayDoesNotAnswerTheNotificationIsRefusedWith503AndNothingIsWritten() {
        payment("pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");
        fake.gateway.statusFailures = 1;

        ProblemException e = assertThrows(ProblemException.class, () -> service.receive(paid("pay_1")));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals("5", e.headers().get("Retry-After"));
        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertTrue(fake.events.rows.isEmpty(), "ничего не записано: шлюз повторит уведомление и оно будет обработано как новое");
        assertEquals(1, count("gateway-unavailable"));

        // Повтор от шлюза после восстановления
        assertEquals(Result.APPLIED, service.receive(paid("pay_1")));
        assertEquals(Payment.CONFIRMED, fake.payments.byOrder.get(ORDER).status());
    }

    @Tag("TC-504")
    @Test
    void aDeclineAfterAConfirmationIsAnAnomalyWithoutAStatusChange() {
        payment("pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");
        service.receive(paid("pay_1"));
        GatewayNotification declined = new GatewayNotification("evt_d", "payment.declined", OCCURRED, "pay_1", ORDER, AMOUNT, "RUB", null, "other");

        assertEquals(Result.ANOMALY, service.receive(declined));

        assertEquals(Payment.CONFIRMED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(1, fake.transaction.events.size());
    }

    @Tag("TC-504")
    @Test
    void thePaymentAlreadyConfirmedByReconciliationMakesTheWebhookAPlainDuplicate() {
        payment("pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");
        PaymentOutcomes direct = new PaymentOutcomes(fake.payments, fake.events, fake.transaction, new ManualClock(NOW), meters);
        direct.apply(new PaymentOutcomes.Application("pay_1", "reconcile.paid", "reconciliation", PaymentOutcomes.Target.CONFIRM, ORDER, OCCURRED, null));

        assertEquals(Result.DUPLICATE, service.receive(paid("pay_1")));

        assertEquals(1, fake.transaction.events.size(), "событие payment.confirmed записано один раз");
    }
    /** Платёж подтверждён, возврат запрошен и принят шлюзом в обработку: ждём {@code refund.completed}. */
    private void refundInProcessing() {
        fake.confirmedPayment(PAYMENT, ORDER, "pay_1", AMOUNT);
        refundOutcomes.request(new RefundOutcomes.Request(ORDER, AMOUNT, "RUB", "late_payment"));
        RefundAttempt attempt = fake.refunds.claimDue(NOW, Duration.ofMinutes(2), 10).get(0);
        refundOutcomes.accepted(attempt, new GatewayRefund("ref_1", false));
    }

    private static GatewayNotification refundCompleted(String paymentId, UUID order, long amount, String currency, String refundId) {
        return new GatewayNotification("evt_r1", "refund.completed", OCCURRED, paymentId, order, amount, currency, refundId, null);
    }

    @Tag("TC-523")
    @Test
    void aRefundCompletedNotificationRefundsThePaymentWithoutAskingTheGateway() {
        refundInProcessing();

        assertEquals(Result.APPLIED, service.receive(refundCompleted("pay_1", ORDER, AMOUNT, "RUB", "ref_1")));

        assertEquals(Payment.REFUNDED, fake.refunds.payment(PAYMENT).status());
        assertEquals(OCCURRED, fake.refunds.payment(PAYMENT).refundedAt());
        assertEquals(1, fake.transaction.events.size());
        assertEquals("payment.refunded", fake.transaction.events.get(0).type());
        assertEquals(false, fake.transaction.events.get(0).data().get("manual"));
        assertEquals(PaymentEventRepository.APPLIED, fake.events.row("pay_1", "refund.completed").outcome());
        assertTrue(fake.gateway.statusCalls.isEmpty(), "возврат товар не отдаёт: запрос статуса не нужен");
        assertEquals(1, count("applied"));
    }

    @Tag("TC-523")
    @Test
    void theSameRefundNotificationAgainIsADuplicateAndWritesNothing() {
        refundInProcessing();
        GatewayNotification n = refundCompleted("pay_1", ORDER, AMOUNT, "RUB", "ref_1");
        service.receive(n);

        assertEquals(Result.DUPLICATE, service.receive(n));

        assertEquals(1, fake.transaction.events.size());
        assertEquals(1, count("duplicate"));
    }

    @Tag("TC-523")
    @Test
    void aRefundNotificationAfterTheAdministratorMarkedTheRefundIsADuplicateNotAnAnomaly() {
        refundInProcessing();
        fake.transaction.execute(() -> refundOutcomes.markRefunded(fake.refunds.payment(PAYMENT), "manual-1", NOW, true));
        int events = fake.transaction.events.size();

        assertEquals(Result.DUPLICATE, service.receive(refundCompleted("pay_1", ORDER, AMOUNT, "RUB", "ref_1")));

        assertEquals(events, fake.transaction.events.size());
        assertEquals(0, meters.find("payment_anomalies_total").counters().size());
    }

    @Tag("TC-523")
    @Test
    void aRefundNotificationForAnUnknownPaymentIsAnAnomalyWithoutBindingTheForeignId() {
        payment(null);

        assertEquals(Result.ANOMALY, service.receive(refundCompleted("pay_9", ORDER, AMOUNT, "RUB", "ref_1")));

        assertNull(fake.payments.byOrder.get(ORDER).gatewayPaymentId(), "чужой идентификатор по возврату к платежу не привязывается");
        assertTrue(fake.unmatched.items.isEmpty());
        assertEquals(1, meters.find("payment_anomalies_total").tag("reason", "refund-unknown-payment").counter().count());
        assertEquals(1, count("anomaly"));
    }

    @Tag("TC-523")
    @Test
    void aRefundNotificationWithAnotherOrderOrAmountChangesNothing() {
        refundInProcessing();
        UUID other = UUID.fromString("0199e0a0-0000-7000-8000-000000000999");

        assertEquals(Result.ANOMALY, service.receive(refundCompleted("pay_1", other, AMOUNT, "RUB", "ref_1")));
        assertEquals(Result.AMOUNT_MISMATCH, service.receive(refundCompleted("pay_1", ORDER, AMOUNT - 100, "RUB", "ref_1")));
        assertEquals(Result.AMOUNT_MISMATCH, service.receive(refundCompleted("pay_1", ORDER, AMOUNT, "USD", "ref_1")));

        assertEquals(Payment.CONFIRMED, fake.refunds.payment(PAYMENT).status());
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Tag("TC-523")
    @Test
    void aRefundNotificationForARefundNobodyRequestedIsAnAnomalyAndTheStatusStays() {
        fake.confirmedPayment(PAYMENT, ORDER, "pay_1", AMOUNT);

        assertEquals(Result.ANOMALY, service.receive(refundCompleted("pay_1", ORDER, AMOUNT, "RUB", "ref_1")));

        assertEquals(Payment.CONFIRMED, fake.refunds.payment(PAYMENT).status());
        assertEquals(PaymentEventRepository.ANOMALY, fake.events.row("pay_1", "refund.completed").outcome());
        assertTrue(fake.transaction.events.isEmpty());
    }
}
