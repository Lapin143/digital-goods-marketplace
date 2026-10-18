package dgm.payment.payments.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.time.ManualClock;
import dgm.payment.payments.FakePayments;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.domain.RefundAttempt;
import dgm.payment.payments.domain.RefundPayment;
import dgm.payment.payments.domain.RefundSchedule;
import dgm.payment.payments.gateway.GatewayRefund;
import dgm.payment.payments.repository.JdbcPaymentRepository;
import dgm.payment.payments.service.RefundOutcomes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Потребитель {@code order.refund-requested} (US-5.7): разбор данных по схеме, первая попытка, игнорирование повторов, неисправимые ошибки. */
@Tag("TC-521")
class RefundRequestHandlerTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PAYMENT = UUID.fromString("0199e0a0-0000-7000-8000-000000000401");
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final long AMOUNT = 299800;

    private FakePayments fake;
    private ManualClock clock;
    private RefundOutcomes outcomes;
    private RefundRequestHandler handler;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        clock = new ManualClock(NOW);
        outcomes = new RefundOutcomes(fake.refunds, fake.events, fake.transaction, RefundSchedule.parse("0s,30s,2m,5m,10m", 0, new Random(1)), clock,
                new SimpleMeterRegistry());
        handler = new RefundRequestHandler(outcomes);
        fake.confirmedPayment(PAYMENT, ORDER, "pay_1", AMOUNT);
    }

    private static Map<String, Object> data() {
        Map<String, Object> money = new HashMap<>();
        money.put("amount", AMOUNT);
        money.put("currency", "RUB");
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", ORDER.toString());
        data.put("amount", money);
        data.put("refundReason", "late_payment");
        return data;
    }

    private static EventEnvelope event(Map<String, Object> data) {
        return new EventEnvelope(UUID.fromString("0199e0a0-0000-7000-8000-000000000a01"), "order-service", "order.refund-requested", NOW, ORDER.toString(), 1,
                "Order", null, "corr-1", data);
    }

    private static Map<String, Object> with(String field, Object value) {
        Map<String, Object> data = data();
        data.put(field, value);
        return data;
    }

    private static Map<String, Object> without(String field) {
        Map<String, Object> data = data();
        data.remove(field);
        return data;
    }

    private static Map<String, Object> money(Object amount, Object currency) {
        Map<String, Object> money = new HashMap<>();
        money.put("amount", amount);
        money.put("currency", currency);
        return money;
    }

    @Test
    void theHandlerIsDescribedByTheEventContract() {
        assertEquals("refund-service", handler.name());
        assertEquals(JdbcPaymentRepository.MODULE, handler.module());
        assertEquals("order.events", handler.topic());
        assertEquals(Set.of("order.refund-requested"), handler.types());
        assertEquals(1, handler.maxSchemaVersion());
    }

    @Test
    void theCommandStartsOneRefundAndARepeatIsIgnoredWithAReason() {
        HandleResult first = handler.handle(event(data()));
        assertTrue(first.applied());

        List<RefundAttempt> attempts = fake.refunds.attemptsOf(PAYMENT);
        assertEquals(1, attempts.size());
        assertEquals(NOW, attempts.get(0).nextAttemptAt());
        assertEquals("late_payment", fake.refunds.payment(PAYMENT).refundReason());

        HandleResult second = handler.handle(event(data()));
        assertFalse(second.applied());
        assertEquals("refund-in-progress", second.reason());
        assertEquals(1, fake.refunds.attemptsOf(PAYMENT).size());
    }

    @Test
    void everyReasonOfTheContractIsAccepted() {
        for (String reason : RefundPayment.REASONS) {
            FakePayments other = new FakePayments();
            RefundOutcomes fresh = new RefundOutcomes(other.refunds, other.events, other.transaction, RefundSchedule.parse("0s", 0, new Random(1)), clock,
                    new SimpleMeterRegistry());
            other.confirmedPayment(PAYMENT, ORDER, "pay_1", AMOUNT);
            assertTrue(new RefundRequestHandler(fresh).handle(event(with("refundReason", reason))).applied(), reason);
            assertEquals(reason, other.refunds.payment(PAYMENT).refundReason());
        }
        assertEquals(Set.of("late_payment", "seller_api_timeout", "dispute_decision"), RefundPayment.REASONS);
    }

    @Test
    void aCommandForARefundedPaymentOrOneInTheQueueIsIgnored() {
        handler.handle(event(data()));
        RefundAttempt attempt = fake.refunds.claimDue(NOW, Duration.ofMinutes(2), 10).get(0);
        outcomes.accepted(attempt, new GatewayRefund("ref_1", true));
        HandleResult refunded = handler.handle(event(data()));
        assertFalse(refunded.applied());
        assertEquals("already-refunded", refunded.reason());

        UUID order2 = UUID.fromString("0199e0a0-0000-7000-8000-000000000302");
        fake.refunds.put(new RefundPayment(UUID.fromString("0199e0a0-0000-7000-8000-000000000402"), order2, "pay_2", AMOUNT, "RUB", Payment.CONFIRMED,
                "late_payment", null, true, NOW, null));
        Map<String, Object> queued = with("orderId", order2.toString());
        HandleResult inQueue = handler.handle(event(queued));
        assertFalse(inQueue.applied());
        assertEquals("in-admin-queue", inQueue.reason());
    }

    @Test
    void aCommandNotForOurDataGoesToTheDeadLetterTopicWithoutRepeats() {
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("orderId", "0199e0a0-0000-7000-8000-000000000999"))));

        UUID order2 = UUID.fromString("0199e0a0-0000-7000-8000-000000000302");
        fake.refunds.put(new RefundPayment(UUID.fromString("0199e0a0-0000-7000-8000-000000000402"), order2, "pay_2", AMOUNT, "RUB", Payment.CREATED, null, null,
                false, null, null));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("orderId", order2.toString()))));

        assertThrows(FatalEventException.class, () -> handler.handle(event(with("amount", money(AMOUNT - 1, "RUB")))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("amount", money(AMOUNT, "USD")))));
        assertTrue(fake.refunds.attempts.isEmpty());
    }

    @Test
    void aBrokenPayloadIsAnUncorrectableError() {
        assertThrows(FatalEventException.class, () -> handler.handle(event(without("orderId"))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("orderId", " "))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("orderId", ORDER.toString().toUpperCase(java.util.Locale.ROOT)))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("orderId", 5))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(without("amount"))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("amount", "299800"))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("amount", money(0, "RUB")))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("amount", money(-5, "RUB")))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("amount", money(10.5, "RUB")))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("amount", money("100", "RUB")))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("amount", money(AMOUNT, "rub")))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("amount", money(AMOUNT, 643)))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(without("refundReason"))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("refundReason", "because"))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(with("refundReason", ""))));
        assertTrue(fake.refunds.attempts.isEmpty());
        assertEquals(Payment.CONFIRMED, fake.refunds.payment(PAYMENT).status());
    }
}
