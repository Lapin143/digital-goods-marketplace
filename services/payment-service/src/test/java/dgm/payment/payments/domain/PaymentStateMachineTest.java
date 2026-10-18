package dgm.payment.payments.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** SM-02: допустимы ровно три перехода, остальные тринадцать из шестнадцати пар отвергаются. */
@Tag("TC-509")
class PaymentStateMachineTest {

    private static final List<String> ALL = List.of(Payment.CREATED, Payment.CONFIRMED, Payment.REJECTED, Payment.REFUNDED);
    private static final Set<String> ALLOWED = Set.of("created>confirmed", "created>rejected", "confirmed>refunded");

    @Test
    void exactlyTheThreeTransitionsOfSm02AreAllowed() {
        int allowed = 0;
        for (String from : ALL) {
            for (String to : ALL) {
                boolean expected = ALLOWED.contains(from + ">" + to);
                assertEquals(expected, PaymentStateMachine.allowed(from, to), from + " → " + to);
                allowed += expected ? 1 : 0;
            }
        }
        assertEquals(3, allowed);
    }

    @Test
    void anUnknownStatusHasNoTransitions() {
        assertFalse(PaymentStateMachine.allowed("expired", Payment.CONFIRMED));
        assertFalse(PaymentStateMachine.allowed(Payment.CREATED, "expired"));
        assertTrue(PaymentStateMachine.terminal("expired"));
    }

    @Test
    void rejectedAndRefundedAreFinalWhileCreatedAndConfirmedAreNot() {
        assertTrue(PaymentStateMachine.terminal(Payment.REJECTED));
        assertTrue(PaymentStateMachine.terminal(Payment.REFUNDED));
        assertFalse(PaymentStateMachine.terminal(Payment.CREATED));
        assertFalse(PaymentStateMachine.terminal(Payment.CONFIRMED));
    }

    @Test
    void thePaymentRecordKnowsWhetherItHasASession() {
        Payment withSession = new Payment(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "pay_1", 100, "RUB", "https://x/pay",
                java.time.Instant.EPOCH, Payment.CREATED);
        assertTrue(withSession.hasSession());
        assertTrue(withSession.isCreated());
        Payment without = new Payment(withSession.id(), withSession.orderId(), null, 100, "RUB", null, null, Payment.CONFIRMED);
        assertFalse(without.hasSession());
        assertFalse(without.isCreated());
    }
}
