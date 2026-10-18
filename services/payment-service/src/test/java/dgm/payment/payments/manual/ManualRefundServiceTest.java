package dgm.payment.payments.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.payment.payments.FakePayments;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.domain.RefundPayment;
import dgm.payment.payments.domain.RefundSchedule;
import dgm.payment.payments.manual.ManualRefundService.Admin;
import dgm.payment.payments.manual.ManualRefundService.Completed;
import dgm.payment.payments.manual.ManualRefundService.Page;
import dgm.payment.payments.service.RefundOutcomes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * Очередь ручных возвратов и отметка «выполнен вручную» (US-5.8, FT-6.4): порядок и страницы очереди, статус, событие и запись аудита в
 * одной транзакции, повторная отметка отвергается.
 */
@Tag("TC-525")
@Tag("TC-526")
class ManualRefundServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final Admin ADMIN = new Admin(UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1"), "203.0.113.7");
    private static final long AMOUNT = 299800;

    private FakePayments fake;
    private ManualClock clock;
    private ManualRefundService service;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        clock = new ManualClock(NOW);
        RefundOutcomes outcomes = new RefundOutcomes(fake.refunds, fake.events, fake.transaction, RefundSchedule.parse("0s,30s,2m,5m,10m", 0, new Random(1)),
                clock, new SimpleMeterRegistry());
        service = new ManualRefundService(fake.refunds, outcomes, fake.transaction, clock);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 400 + n));
    }

    /** Платёж в очереди администратора, переданный на {@code minutes} минут позже начала. */
    private RefundPayment queued(int n, int minutes) {
        RefundPayment payment = new RefundPayment(id(n), UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 300 + n)), "pay_" + n, AMOUNT, "RUB",
                Payment.CONFIRMED, "late_payment", null, true, NOW.plus(Duration.ofMinutes(minutes)), null);
        fake.refunds.put(payment);
        return payment;
    }

    @Test
    void theQueueShowsTheOldestFirstAndOnlyRefundsWaitingForAnAdministrator() {
        queued(3, 30);
        queued(1, 10);
        queued(2, 20);
        fake.confirmedPayment(id(9), UUID.fromString("0199e0a0-0000-7000-8000-000000000309"), "pay_9", AMOUNT);

        Page page = service.queue(10, Optional.empty());

        assertEquals(List.of(id(1), id(2), id(3)), page.items().stream().map(r -> r.paymentId()).toList());
        assertEquals(10, page.limit());
        assertTrue(page.next().isEmpty());
        assertEquals("late_payment", page.items().get(0).refundReason());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), page.items().get(0).escalatedAt());
    }

    @Test
    void thePagesFollowEachOtherWithoutRepeatsAndTheLastOneHasNoCursor() {
        for (int n = 1; n <= 5; n++) {
            queued(n, n);
        }

        Page first = service.queue(2, Optional.empty());
        assertEquals(List.of(id(1), id(2)), first.items().stream().map(r -> r.paymentId()).toList());
        assertTrue(first.next().isPresent());

        Page second = service.queue(2, first.next());
        assertEquals(List.of(id(3), id(4)), second.items().stream().map(r -> r.paymentId()).toList());

        Page last = service.queue(2, second.next());
        assertEquals(List.of(id(5)), last.items().stream().map(r -> r.paymentId()).toList());
        assertTrue(last.next().isEmpty());
    }

    @Test
    void refundsEscalatedAtTheSameMomentKeepAStableOrderById() {
        queued(2, 5);
        queued(1, 5);
        queued(3, 5);

        Page first = service.queue(2, Optional.empty());
        Page second = service.queue(2, first.next());

        assertEquals(List.of(id(1), id(2), id(3)),
                List.of(first.items().get(0).paymentId(), first.items().get(1).paymentId(), second.items().get(0).paymentId()));
    }

    @Test
    void anEmptyQueueIsAnEmptyPage() {
        Page page = service.queue(20, Optional.empty());
        assertTrue(page.items().isEmpty());
        assertTrue(page.next().isEmpty());
    }

    @Test
    void theMarkRefundsThePaymentWritesTheEventAndTheAuditRecordInOneTransaction() {
        queued(1, 10);
        clock.advance(Duration.ofMinutes(45));

        Completed done = service.complete(ADMIN, id(1), "manual-77");

        assertEquals(id(1), done.paymentId());
        assertEquals(UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 301)), done.orderId());
        assertEquals(clock.instant(), done.refundedAt());
        RefundPayment payment = fake.refunds.payment(id(1));
        assertEquals(Payment.REFUNDED, payment.status());
        assertEquals("manual-77", payment.gatewayRefundId());
        assertFalse(payment.inAdminQueue());
        assertEquals(1, fake.transaction.committed);

        assertEquals(2, fake.transaction.events.size());
        FakePayments.Tx.Event refunded = fake.transaction.events.get(0);
        assertEquals("payment.events", refunded.topic());
        assertEquals("payment.refunded", refunded.type());
        assertEquals(true, refunded.data().get("manual"));
        assertEquals("late_payment", refunded.data().get("refundReason"));

        FakePayments.Tx.Event audit = fake.transaction.events.get(1);
        assertEquals("audit.events", audit.topic());
        assertEquals("audit.recorded", audit.type());
        Map<String, Object> data = audit.data();
        assertEquals("refund.marked_manual", data.get("action"));
        assertEquals("admin", data.get("actorRole"));
        assertEquals(ADMIN.id().toString(), data.get("actorId"));
        assertEquals("203.0.113.7", data.get("actorIp"));
        assertEquals("Payment", data.get("objectType"));
        assertEquals(id(1).toString(), data.get("objectId"));
        assertEquals(Set.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes", "actorIp", "actorIpHashed", "correlationId"),
                data.keySet(), "в записи аудита нет персональных полей");
        assertEquals(2, ((List<?>) data.get("changes")).size());
    }

    @Test
    void theExternalRefundIdIsOptional() {
        queued(1, 10);

        service.complete(ADMIN, id(1), null);

        assertEquals(Payment.REFUNDED, fake.refunds.payment(id(1)).status());
        assertNull(fake.refunds.payment(id(1)).gatewayRefundId());
    }

    @Test
    void aSecondMarkIsRefusedAndWritesNothingMore() {
        queued(1, 10);
        service.complete(ADMIN, id(1), "manual-77");
        int events = fake.transaction.events.size();

        ProblemException e = assertThrows(ProblemException.class, () -> service.complete(ADMIN, id(1), "manual-77"));

        assertEquals(ProblemType.REFUND_NOT_ALLOWED, e.type());
        assertEquals(409, e.type().status());
        assertEquals(events, fake.transaction.events.size(), "второго события и второй записи аудита нет");
        assertEquals(1, fake.transaction.rolledBack);
    }

    @Test
    void aRefundNotWaitingForAnAdministratorOrUnknownCannotBeMarked() {
        fake.confirmedPayment(id(1), UUID.fromString("0199e0a0-0000-7000-8000-000000000301"), "pay_1", AMOUNT);
        ProblemException notQueued = assertThrows(ProblemException.class, () -> service.complete(ADMIN, id(1), null));
        assertEquals(ProblemType.REFUND_NOT_ALLOWED, notQueued.type());

        ProblemException unknown = assertThrows(ProblemException.class, () -> service.complete(ADMIN, id(7), null));
        assertEquals(ProblemType.NOT_FOUND, unknown.type());

        assertEquals(Payment.CONFIRMED, fake.refunds.payment(id(1)).status());
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Test
    void anExternalRefundIdAlreadyLinkedToAnotherPaymentIsAConflict() {
        queued(1, 10);
        fake.refunds.failOnMark = new DuplicateKeyException("uq_payment_gateway_refund_id");

        ProblemException e = assertThrows(ProblemException.class, () -> service.complete(ADMIN, id(1), "manual-77"));

        assertEquals(ProblemType.REFUND_NOT_ALLOWED, e.type());
        assertEquals(Payment.CONFIRMED, fake.refunds.payment(id(1)).status());
        assertTrue(fake.transaction.events.isEmpty());
    }
}
