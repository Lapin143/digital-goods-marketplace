package dgm.payment.payments.refund;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.payment.payments.FakePayments;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.domain.RefundAttempt;
import dgm.payment.payments.domain.RefundSchedule;
import dgm.payment.payments.repository.RefundRepository;
import dgm.payment.payments.service.RefundOutcomes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Исполнитель возвратов (ADR-015, ADR-011): аренда наступивших попыток, вызов шлюза вне транзакции, запись итога, повторы по расписанию и
 * передача администратору после пятого отказа.
 */
@Tag("TC-522")
@Tag("TC-524")
class RefundExecutorTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PAYMENT = UUID.fromString("0199e0a0-0000-7000-8000-000000000401");
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final long AMOUNT = 299800;

    private FakePayments fake;
    private ManualClock clock;
    private RefundOutcomes outcomes;
    private RefundExecutor executor;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        clock = new ManualClock(NOW);
        outcomes = new RefundOutcomes(fake.refunds, fake.events, fake.transaction, RefundSchedule.parse("0s,30s,2m,5m,10m", 0, new Random(1)), clock,
                new SimpleMeterRegistry());
        executor = new RefundExecutor(fake.refunds, fake.gateway, outcomes, new RefundSettings(Duration.ofMinutes(2), 10, Duration.ofMinutes(5)), clock);
        fake.confirmedPayment(PAYMENT, ORDER, "pay_1", AMOUNT);
    }

    private void request() {
        outcomes.request(new RefundOutcomes.Request(ORDER, AMOUNT, "RUB", "late_payment"));
    }

    @Test
    void aDueAttemptCallsTheGatewayOutsideTheTransactionAndTheInstantRefundIsRecorded() {
        request();

        assertEquals(1, executor.runOnce());

        assertEquals(List.of(PAYMENT), fake.gateway.refundCalls);
        assertEquals(Payment.REFUNDED, fake.refunds.payment(PAYMENT).status());
        assertEquals("ref_1", fake.refunds.payment(PAYMENT).gatewayRefundId());
        assertEquals("payment.refunded", fake.transaction.events.get(0).type());
        assertEquals(0, executor.runOnce(), "закрытая попытка заново не берётся");
        assertEquals(1, fake.gateway.refundCalls.size());
    }

    @Test
    void aRefundTheGatewayAcceptedForProcessingWaitsForTheConfirmation() {
        fake.gateway.refundInstant = false;
        request();

        executor.runOnce();

        assertEquals(Payment.CONFIRMED, fake.refunds.payment(PAYMENT).status());
        assertEquals(RefundAttempt.PROCESSING, fake.refunds.attemptsOf(PAYMENT).get(0).status());
        assertEquals("ref_1", fake.refunds.payment(PAYMENT).gatewayRefundId());
        assertTrue(fake.transaction.events.isEmpty());
        assertEquals(0, executor.runOnce());
    }

    @Test
    void anUnavailableGatewayLeavesTheAttemptFailedAndTheNextOneWaitsForItsPause() {
        fake.gateway.refundFailures = 1;
        request();

        executor.runOnce();

        List<RefundAttempt> attempts = fake.refunds.attemptsOf(PAYMENT);
        assertEquals(RefundAttempt.FAILED, attempts.get(0).status());
        assertEquals("gateway_unavailable", attempts.get(0).errorCode());
        assertEquals(RefundAttempt.PENDING, attempts.get(1).status());
        assertEquals(NOW.plusSeconds(30), attempts.get(1).nextAttemptAt());
        assertEquals(0, executor.runOnce(), "пауза ещё не прошла");

        clock.advance(Duration.ofSeconds(31));
        assertEquals(1, executor.runOnce());
        assertEquals(Payment.REFUNDED, fake.refunds.payment(PAYMENT).status());
        assertEquals(2, fake.gateway.refundCalls.size());
        assertEquals(1, fake.gateway.refundsCreated.size(), "ключ шлюза один на все попытки: второго возврата нет");
    }

    @Test
    void aRefusalOfTheGatewayIsRecordedWithItsStatus() {
        fake.gateway.refundRejects = 1;
        request();

        executor.runOnce();

        assertEquals("gateway_rejected_422", fake.refunds.attemptsOf(PAYMENT).get(0).errorCode());
        assertEquals(Payment.CONFIRMED, fake.refunds.payment(PAYMENT).status());
    }

    @Test
    void fiveRefusalsEndInTheAdminQueueAndNoSixthAttemptIsMade() {
        fake.gateway.refundFailures = 5;
        request();

        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(11));
            assertEquals(1, executor.runOnce(), "проход " + (i + 1));
        }

        assertTrue(fake.refunds.payment(PAYMENT).inAdminQueue());
        assertEquals(5, fake.gateway.refundCalls.size());
        assertEquals("payment.refund-escalated", fake.transaction.events.get(0).type());
        clock.advance(Duration.ofHours(1));
        assertEquals(0, executor.runOnce(), "в очереди администратора автоматических попыток нет");
        assertEquals(5, fake.gateway.refundCalls.size());
    }

    @Test
    void aPaymentRefundedByAnotherPathIsNotRefundedTwice() {
        request();
        fake.transaction.execute(() -> outcomes.markRefunded(fake.refunds.payment(PAYMENT), "manual-1", NOW, true));

        executor.runOnce();

        assertTrue(fake.gateway.refundCalls.isEmpty(), "платёж уже возвращён: шлюз не вызывается");
    }

    @Test
    void anAttemptOfACrashedProcessReturnsAfterTheLeaseAndTheKeyKeepsTheRefundSingle() {
        request();
        List<RefundAttempt> taken = fake.refunds.claimDue(NOW, Duration.ofMinutes(2), 10);
        assertEquals(1, taken.size());
        assertEquals(0, executor.runOnce(), "аренда ещё действует: второй экземпляр попытку не берёт");

        clock.advance(Duration.ofMinutes(3));
        assertEquals(1, executor.runOnce());

        assertEquals(Payment.REFUNDED, fake.refunds.payment(PAYMENT).status());
        assertEquals(1, fake.gateway.refundsCreated.size());
    }

    @Test
    void thePassTakesNoMoreThanTheBatch() {
        RefundExecutor small = new RefundExecutor(fake.refunds, fake.gateway, outcomes, new RefundSettings(Duration.ofMinutes(2), 2, Duration.ofMinutes(5)), clock);
        for (int i = 0; i < 3; i++) {
            UUID order = UUID.fromString("0199e0a0-0000-7000-8000-00000000030" + (i + 2));
            UUID payment = UUID.fromString("0199e0a0-0000-7000-8000-00000000040" + (i + 2));
            fake.confirmedPayment(payment, order, "pay_" + (i + 2), AMOUNT);
            outcomes.request(new RefundOutcomes.Request(order, AMOUNT, "RUB", "late_payment"));
        }

        assertEquals(2, small.runOnce());
        assertEquals(1, small.runOnce());
        assertEquals(0, small.runOnce());
    }

    @Test
    void aFailingPassIsLoggedAndTheSchedulerSurvives() {
        RefundRepository broken = (RefundRepository) Proxy.newProxyInstance(RefundRepository.class.getClassLoader(), new Class<?>[] {RefundRepository.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("база недоступна");
                });
        RefundExecutor failing = new RefundExecutor(broken, fake.gateway, outcomes, RefundSettings.defaults(), clock);

        assertThrows(IllegalStateException.class, failing::runOnce);
        assertDoesNotThrow(failing::scheduled);
    }

    @Test
    void theSettingsRefuseValuesThatBreakTheExecutor() {
        assertThrows(IllegalStateException.class, () -> new RefundSettings(Duration.ZERO, 10, Duration.ofMinutes(5)));
        assertThrows(IllegalStateException.class, () -> new RefundSettings(null, 10, Duration.ofMinutes(5)));
        assertThrows(IllegalStateException.class, () -> new RefundSettings(Duration.ofMinutes(-1), 10, Duration.ofMinutes(5)));
        assertThrows(IllegalStateException.class, () -> new RefundSettings(Duration.ofMinutes(2), 0, Duration.ofMinutes(5)));
        assertThrows(IllegalStateException.class, () -> new RefundSettings(Duration.ofMinutes(2), 101, Duration.ofMinutes(5)));
        assertThrows(IllegalStateException.class, () -> new RefundSettings(Duration.ofMinutes(2), 10, Duration.ofSeconds(-1)));
        assertThrows(IllegalStateException.class, () -> new RefundSettings(Duration.ofMinutes(2), 10, null));
        assertEquals(new RefundSettings(Duration.ofMinutes(2), 10, Duration.ZERO).processingAge(), Duration.ZERO);
        assertEquals(Duration.ofMinutes(2), RefundSettings.defaults().lease());
    }
}
