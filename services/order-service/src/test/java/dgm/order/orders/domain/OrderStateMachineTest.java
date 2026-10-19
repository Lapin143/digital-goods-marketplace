package dgm.order.orders.domain;

import static dgm.order.orders.domain.CancelReason.OUT_OF_STOCK;
import static dgm.order.orders.domain.CancelReason.PAYMENT_DECLINED;
import static dgm.order.orders.domain.CancelReason.PAYMENT_SESSION_FAILED;
import static dgm.order.orders.domain.CancelReason.RESERVATION_EXPIRED;
import static dgm.order.orders.domain.CancelReason.SELLER_API_TIMEOUT;
import static dgm.order.orders.domain.CancelReason.SYSTEM_ERROR;
import static dgm.order.orders.domain.OrderStatus.AWAITING_PAYMENT;
import static dgm.order.orders.domain.OrderStatus.CANCELLED;
import static dgm.order.orders.domain.OrderStatus.CREATED;
import static dgm.order.orders.domain.OrderStatus.ISSUED;
import static dgm.order.orders.domain.OrderStatus.PAID;
import static dgm.order.orders.domain.OrderStatus.REFUNDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.order.orders.domain.OrderStateMachine.Transition;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SM-01: девять переходов R1 находятся по статусам и причине, причина выбирает между T3 и T12, T5 и T6, остальные пары из тридцати шести
 * запрещены, T10 и T11 (R2) в коде описаны как запрещённые. Множество разрешённых пар совпадает с триггером базы
 * {@code trg_orders_status_transition}.
 */
@Tag("TC-510")
class OrderStateMachineTest {

    private static String id(String from, String to, String reason) {
        return OrderStateMachine.find(from, to, reason).map(Transition::id).orElse("нет");
    }

    @Test
    void theTableHoldsTheNineTransitionsOfRelease1InTheOrderOfTheDiagram() {
        assertEquals(List.of("T2", "T3", "T12", "T4", "T5", "T6", "T7", "T8", "T9"), OrderStateMachine.allowed().stream().map(Transition::id).toList());
    }

    @Test
    void everyTransitionIsFoundByItsStatusesAndReason() {
        assertEquals("T2", id(CREATED, AWAITING_PAYMENT, null));
        assertEquals("T3", id(CREATED, CANCELLED, OUT_OF_STOCK));
        assertEquals("T12", id(CREATED, CANCELLED, PAYMENT_SESSION_FAILED));
        assertEquals("T12", id(CREATED, CANCELLED, SYSTEM_ERROR));
        assertEquals("T4", id(AWAITING_PAYMENT, PAID, null));
        assertEquals("T5", id(AWAITING_PAYMENT, CANCELLED, PAYMENT_DECLINED));
        assertEquals("T6", id(AWAITING_PAYMENT, CANCELLED, RESERVATION_EXPIRED));
        assertEquals("T7", id(CANCELLED, PAID, null));
        assertEquals("T8", id(CANCELLED, REFUNDED, null));
        assertEquals("T9", id(PAID, ISSUED, null));
    }

    @Test
    void theReasonOfTheCancellationChoosesTheTransition() {
        for (String wrong : new String[] {PAYMENT_DECLINED, RESERVATION_EXPIRED, SELLER_API_TIMEOUT, null, "other"}) {
            assertEquals("нет", id(CREATED, CANCELLED, wrong), "из «создан» с причиной " + wrong);
        }
        for (String wrong : new String[] {OUT_OF_STOCK, PAYMENT_SESSION_FAILED, SYSTEM_ERROR, SELLER_API_TIMEOUT, null, "other"}) {
            assertEquals("нет", id(AWAITING_PAYMENT, CANCELLED, wrong), "из «ожидает оплаты» с причиной " + wrong);
        }
    }

    @Test
    void onlyTheSevenPairsOfTheDatabaseTriggerHaveATransitionWhateverTheReason() {
        List<String> statuses = List.of(CREATED, AWAITING_PAYMENT, PAID, ISSUED, CANCELLED, REFUNDED);
        Set<String> reasons = new HashSet<>(CancelReason.ALL);
        reasons.add(null);
        Set<String> pairs = new TreeSet<>();
        for (String from : statuses) {
            for (String to : statuses) {
                for (String reason : reasons) {
                    if (OrderStateMachine.find(from, to, reason).isPresent()) {
                        pairs.add(from + ">" + to);
                    }
                }
            }
        }
        assertEquals(Set.of("created>awaiting_payment", "created>cancelled", "awaiting_payment>paid", "awaiting_payment>cancelled", "cancelled>paid",
                "cancelled>refunded", "paid>issued"), pairs);
    }

    @Test
    void nothingLeadsBackToCreatedAndNothingLeavesTheFinalStatuses() {
        for (String from : List.of(CREATED, AWAITING_PAYMENT, PAID, ISSUED, CANCELLED, REFUNDED)) {
            assertEquals(Optional.empty(), OrderStateMachine.find(from, CREATED, null), "в «создан» из " + from);
        }
        for (String to : List.of(CREATED, AWAITING_PAYMENT, PAID, ISSUED, CANCELLED, REFUNDED)) {
            assertEquals(Optional.empty(), OrderStateMachine.find(REFUNDED, to, SYSTEM_ERROR), "из «возвращён» в " + to);
            assertEquals(Optional.empty(), OrderStateMachine.find(ISSUED, to, SYSTEM_ERROR), "из «выдан» в " + to);
        }
    }

    @Test
    void transitionsOfRelease2AreDescribedButNeverAllowed() {
        assertEquals(List.of("T10", "T11"), OrderStateMachine.later().stream().map(Transition::id).toList());
        assertTrue(OrderStateMachine.isLater(PAID, CANCELLED));
        assertTrue(OrderStateMachine.isLater(ISSUED, REFUNDED));
        assertFalse(OrderStateMachine.isLater(CREATED, PAID));
        assertEquals("нет", id(PAID, CANCELLED, SELLER_API_TIMEOUT));
        assertEquals("нет", id(ISSUED, REFUNDED, null));
        for (Transition later : OrderStateMachine.later()) {
            assertFalse(OrderStateMachine.allowed().contains(later));
        }
    }

    @Test
    void onlyCancellationsCarryReasons() {
        for (Transition t : OrderStateMachine.allowed()) {
            assertEquals(t.isCancellation(), !t.reasons().isEmpty(), t.id());
        }
    }

    @Test
    void requireThrowsForAForbiddenTransitionAndNamesIt() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> OrderStateMachine.require(PAID, CANCELLED, SELLER_API_TIMEOUT));
        assertTrue(e.getMessage().contains("paid"), e.getMessage());
        assertTrue(e.getMessage().contains("cancelled"), e.getMessage());
        assertEquals("T4", OrderStateMachine.require(AWAITING_PAYMENT, PAID, null).id());
    }

    @Test
    void theUnpaidStatusesAreCreatedAndAwaitingPayment() {
        assertTrue(OrderStatus.isUnpaid(CREATED));
        assertTrue(OrderStatus.isUnpaid(AWAITING_PAYMENT));
        for (String status : List.of(PAID, ISSUED, CANCELLED, REFUNDED)) {
            assertFalse(OrderStatus.isUnpaid(status), status);
        }
    }
}
