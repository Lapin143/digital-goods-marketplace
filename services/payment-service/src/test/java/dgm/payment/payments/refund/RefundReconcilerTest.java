package dgm.payment.payments.refund;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.payment.payments.FakePayments;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.domain.RefundAttempt;
import dgm.payment.payments.domain.RefundSchedule;
import dgm.payment.payments.gateway.GatewayStatus;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.service.RefundOutcomes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Сверка возвратов (ADR-015): шлюз принял возврат, уведомление не пришло; сверка спрашивает шлюз и применяет итог общим кодом. */
@Tag("TC-523")
class RefundReconcilerTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PAYMENT = UUID.fromString("0199e0a0-0000-7000-8000-000000000401");
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final long AMOUNT = 299800;

    private FakePayments fake;
    private ManualClock clock;
    private RefundReconciler reconciler;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        clock = new ManualClock(NOW);
        RefundOutcomes outcomes = new RefundOutcomes(fake.refunds, fake.events, fake.transaction, RefundSchedule.parse("0s,30s,2m,5m,10m", 0, new Random(1)),
                clock, new SimpleMeterRegistry());
        RefundSettings settings = new RefundSettings(Duration.ofMinutes(2), 10, Duration.ofMinutes(5));
        fake.gateway.refundInstant = false;
        fake.confirmedPayment(PAYMENT, ORDER, "pay_1", AMOUNT);
        outcomes.request(new RefundOutcomes.Request(ORDER, AMOUNT, "RUB", "late_payment"));
        new RefundExecutor(fake.refunds, fake.gateway, outcomes, settings, clock).runOnce();
        reconciler = new RefundReconciler(fake.refunds, fake.gateway, outcomes, settings, clock);
    }

    @Test
    void aRefundAcceptedMomentsAgoIsNotAskedAbout() {
        clock.advance(Duration.ofMinutes(4));

        assertEquals(0, reconciler.runOnce());

        assertTrue(fake.gateway.statusCalls.isEmpty());
    }

    @Test
    void anOldRefundThatTheGatewayCompletedIsConfirmedOnceWithItsOwnEventType() {
        clock.advance(Duration.ofMinutes(6));
        fake.gateway.know("pay_1", GatewayStatus.REFUNDED, AMOUNT, "RUB");

        assertEquals(1, reconciler.runOnce());

        assertEquals(Payment.REFUNDED, fake.refunds.payment(PAYMENT).status());
        assertEquals(RefundAttempt.SUCCEEDED, fake.refunds.attemptsOf(PAYMENT).get(0).status());
        assertEquals(PaymentEventRepository.APPLIED, fake.events.row("pay_1", RefundReconciler.EVENT_TYPE).outcome());
        assertEquals(1, fake.transaction.events.size());
        assertEquals("payment.refunded", fake.transaction.events.get(0).type());
        assertEquals(1, fake.gateway.retryingCalls, "сверка спрашивает шлюз с повторами клиента, у неё времени больше, чем у вебхука");
        assertEquals(0, reconciler.runOnce(), "платёж больше не ждёт подтверждения");
        assertEquals(1, fake.transaction.events.size());
    }

    @Test
    void aRefundStillInProgressAtTheGatewayStaysAndIsCheckedAgain() {
        clock.advance(Duration.ofMinutes(6));
        fake.gateway.know("pay_1", GatewayStatus.REFUNDING, AMOUNT, "RUB");

        assertEquals(0, reconciler.runOnce());
        assertEquals(Payment.CONFIRMED, fake.refunds.payment(PAYMENT).status());

        fake.gateway.know("pay_1", GatewayStatus.REFUNDED, AMOUNT, "RUB");
        assertEquals(1, reconciler.runOnce());
    }

    @Test
    void aGatewayThatDoesNotAnswerOrDoesNotKnowThePaymentLeavesItForTheNextPass() {
        clock.advance(Duration.ofMinutes(6));
        fake.gateway.statusFailures = 1;
        assertEquals(0, reconciler.runOnce());

        assertEquals(0, reconciler.runOnce(), "шлюз ответил «платежа нет»: это не подтверждение");
        assertEquals(Payment.CONFIRMED, fake.refunds.payment(PAYMENT).status());
        assertEquals(2, fake.gateway.statusCalls.size());

        fake.gateway.know("pay_1", GatewayStatus.REFUNDED, AMOUNT, "RUB");
        assertEquals(1, reconciler.runOnce());
    }

    @Test
    void aFailingPassIsLoggedAndTheSchedulerSurvives() {
        clock.advance(Duration.ofMinutes(6));
        fake.gateway.know("pay_1", GatewayStatus.REFUNDED, AMOUNT, "RUB");
        fake.refunds.failOnMark = new IllegalStateException("база недоступна");

        assertDoesNotThrow(reconciler::scheduled);
        assertEquals(Payment.CONFIRMED, fake.refunds.payment(PAYMENT).status());
        assertTrue(fake.transaction.events.isEmpty());
        assertEquals(1, fake.transaction.rolledBack);
    }
}
