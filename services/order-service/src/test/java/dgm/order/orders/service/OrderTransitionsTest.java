package dgm.order.orders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.order.orders.domain.CancelReason;
import dgm.order.orders.domain.NewOrder;
import dgm.order.orders.domain.OrderStatus;
import dgm.order.orders.domain.StoredOrder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Переходы заказа в одной короткой транзакции: T1 под блокировкой покупателя с лимитом неоплаченных (Р-142), T2 с событием
 * {@code order.created}, отмены T3, T5, T6, T12 с событием {@code order.cancelled}, оплата T4 и T7 с {@code order.paid}, команда возврата
 * {@code order.refund-requested}, возврат T8, выдача T9, обезличивание адреса. Повтор и запоздавший вызов ничего не меняют.
 */
class OrderTransitionsTest {

    private static final Instant T0 = Instant.parse("2026-10-18T12:00:00.123Z");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final Instant RESERVE_UNTIL = T0.plus(Duration.ofMinutes(15));
    private static final Instant SESSION_UNTIL = T0.plus(Duration.ofMinutes(12));
    private static final String URL = "https://localhost:8443/payment/pay/pay_1";

    private FakeOrderStore store;
    private SimpleMeterRegistry meters;
    private OrderTransitions transitions;

    @BeforeEach
    void setUp() {
        store = new FakeOrderStore();
        meters = new SimpleMeterRegistry();
        transitions = new OrderTransitions(store, store, new OrderSettings(3, Duration.ofMinutes(15), Duration.ofMinutes(12), 200), meters);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 300 + n));
    }

    private static NewOrder draft(int n, UUID buyer) {
        return new NewOrder(id(n), buyer, SELLER, PRODUCT, "Лунная тропа: ключ Steam", 2, 149_900, 299_800, 200, 5_996, "RUB", "buyer@mail.example", T0);
    }

    private static StoredOrder existing(int n, UUID buyer, String status) {
        return new StoredOrder(id(n), 900L + n, buyer, SELLER, PRODUCT, "Игра", 1, 100_00, 100_00, 2_00, "RUB", status,
                OrderStatus.CANCELLED.equals(status) ? CancelReason.PAYMENT_DECLINED : null, "buyer@mail.example", null, null, null, T0,
                OrderStatus.PAID.equals(status) || OrderStatus.ISSUED.equals(status) ? T0 : null, OrderStatus.ISSUED.equals(status) ? T0 : null);
    }

    private double counter(String name, String tag, String value) {
        var counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    // ------------------------------------------------------------------------------------------------ TC-511: T1, T2

    @Tag("TC-511")
    @Test
    void placeInsertsTheOrderInCreatedAndPublishesNothingYet() {
        StoredOrder order = transitions.place(draft(1, BUYER));

        assertEquals(id(1), order.id());
        assertEquals(1001L, order.number());
        assertEquals(OrderStatus.CREATED, order.status());
        assertEquals(299_800L, order.amount());
        assertEquals(5_996L, order.commission());
        assertEquals("buyer@mail.example", order.deliveryAddress());
        assertNull(order.reserveUntil());
        assertNull(order.paymentSessionUrl());
        assertEquals(0, store.events().size(), "order.created издаётся при T2, а не при T1");
        assertEquals(1.0, counter("orders_created_total", "stage", "placed"));
    }

    @Tag("TC-511")
    @Test
    void awaitPaymentFixesTheDeadlinesAndThePayPageAndPublishesOrderCreatedInTheSameTransaction() {
        transitions.place(draft(1, BUYER));
        int before = store.transactions();

        Optional<StoredOrder> awaiting = transitions.awaitPayment(id(1), RESERVE_UNTIL, SESSION_UNTIL, URL);

        assertTrue(awaiting.isPresent());
        assertEquals(1, store.transactions() - before, "переход и событие в одной транзакции");
        StoredOrder order = awaiting.get();
        assertEquals(OrderStatus.AWAITING_PAYMENT, order.status());
        assertEquals(RESERVE_UNTIL, order.reserveUntil());
        assertEquals(SESSION_UNTIL, order.sessionUntil());
        assertEquals(URL, order.paymentSessionUrl());

        assertEquals(1, store.events().size());
        FakeOrderStore.Published event = store.events().get(0);
        assertEquals("order.events", event.topic());
        assertEquals("Order", event.aggregateType());
        assertEquals(id(1).toString(), event.aggregateId());
        assertEquals("order.created", event.eventType());
        assertEquals(1, event.schemaVersion());
        Map<String, Object> data = event.data();
        assertEquals(Set.of("orderId", "orderNumber", "buyerId", "sellerId", "productId", "quantity", "unitPrice", "total", "commission",
                "reserveExpiresAt", "paymentSessionExpiresAt"), data.keySet());
        assertEquals(id(1).toString(), data.get("orderId"));
        assertEquals(1001L, data.get("orderNumber"));
        assertEquals(BUYER.toString(), data.get("buyerId"));
        assertEquals(SELLER.toString(), data.get("sellerId"));
        assertEquals(PRODUCT.toString(), data.get("productId"));
        assertEquals(2, data.get("quantity"));
        assertEquals(Map.of("amount", 149_900L, "currency", "RUB"), data.get("unitPrice"));
        assertEquals(Map.of("amount", 299_800L, "currency", "RUB"), data.get("total"));
        assertEquals(Map.of("amount", 5_996L, "currency", "RUB"), data.get("commission"));
        assertEquals("2026-10-18T12:15:00.123Z", data.get("reserveExpiresAt"));
        assertEquals("2026-10-18T12:12:00.123Z", data.get("paymentSessionExpiresAt"));
        assertFalse(data.containsKey("deliveryAddress"), "адрес доставки в событие не попадает");
        assertFalse(data.toString().contains("buyer@mail.example"));
    }

    @Tag("TC-511")
    @Test
    void aSecondAwaitPaymentAndAnAwaitPaymentOfACancelledOrderChangeNothing() {
        transitions.place(draft(1, BUYER));
        assertTrue(transitions.awaitPayment(id(1), RESERVE_UNTIL, SESSION_UNTIL, URL).isPresent());

        assertTrue(transitions.awaitPayment(id(1), RESERVE_UNTIL, SESSION_UNTIL, URL).isEmpty());
        assertEquals(1, store.events("order.created").size());

        transitions.place(draft(2, BUYER));
        assertTrue(transitions.cancel(id(2), CancelReason.SYSTEM_ERROR).isPresent());
        assertTrue(transitions.awaitPayment(id(2), RESERVE_UNTIL, SESSION_UNTIL, URL).isEmpty());
        assertEquals(OrderStatus.CANCELLED, store.get(id(2)).status());
        assertEquals(1, store.events("order.created").size());
    }

    @Tag("TC-511")
    @Test
    void awaitPaymentOfAnUnknownOrderIsAnError() {
        assertThrows(IllegalStateException.class, () -> transitions.awaitPayment(id(9), RESERVE_UNTIL, SESSION_UNTIL, URL));
    }

    // ------------------------------------------------------------------------------------------------ TC-512: лимит

    @Tag("TC-512")
    @Test
    void theFourthUnpaidOrderIsRefusedWithTheLimitAndNothingIsInserted() {
        transitions.place(draft(1, BUYER));
        transitions.place(draft(2, BUYER));
        transitions.place(draft(3, BUYER));
        assertTrue(transitions.awaitPayment(id(3), RESERVE_UNTIL, SESSION_UNTIL, URL).isPresent(), "«ожидает оплаты» тоже считается неоплаченным");

        ProblemException e = assertThrows(ProblemException.class, () -> transitions.place(draft(4, BUYER)));

        assertEquals(ProblemType.UNPAID_ORDERS_LIMIT, e.type());
        assertEquals(409, e.type().status());
        assertEquals(3, e.extensions().get("limit"));
        assertEquals(3, store.all().size());
        assertEquals(1.0, counter("orders_rejected_total", "reason", "unpaid-orders-limit"));
    }

    @Tag("TC-512")
    @Test
    void theLimitIsTakenFromTheSettingsAndCountsOnlyTheOwnerOfTheOrder() {
        OrderTransitions strict = new OrderTransitions(store, store, new OrderSettings(1, Duration.ofMinutes(15), Duration.ofMinutes(12), 200), meters);
        strict.place(draft(1, BUYER));

        ProblemException e = assertThrows(ProblemException.class, () -> strict.place(draft(2, BUYER)));
        assertEquals(1, e.extensions().get("limit"));
        assertEquals(OrderStatus.CREATED, strict.place(draft(3, OTHER)).status(), "заказы другого покупателя не считаются");
    }

    @Tag("TC-512")
    @Test
    void paidIssuedCancelledAndRefundedOrdersDoNotCountAndFreeTheSlot() {
        store.seed(existing(1, BUYER, OrderStatus.PAID)).seed(existing(2, BUYER, OrderStatus.ISSUED)).seed(existing(3, BUYER, OrderStatus.CANCELLED))
                .seed(existing(4, BUYER, OrderStatus.REFUNDED));

        transitions.place(draft(5, BUYER));
        transitions.place(draft(6, BUYER));
        assertEquals(OrderStatus.CREATED, transitions.place(draft(7, BUYER)).status());

        assertThrows(ProblemException.class, () -> transitions.place(draft(8, BUYER)));
    }

    @Tag("TC-512")
    @Test
    void anOrderThatIsCancelledFreesItsSlot() {
        transitions.place(draft(1, BUYER));
        transitions.place(draft(2, BUYER));
        transitions.place(draft(3, BUYER));
        assertThrows(ProblemException.class, () -> transitions.place(draft(4, BUYER)));

        assertTrue(transitions.cancel(id(2), CancelReason.OUT_OF_STOCK).isPresent());
        assertEquals(OrderStatus.CREATED, transitions.place(draft(4, BUYER)).status());
    }

    @Tag("TC-512")
    @Test
    void theCountAndTheInsertRunInOneTransactionUnderTheLockOfTheBuyer() {
        int before = store.transactions();
        transitions.place(draft(1, BUYER));
        transitions.place(draft(2, OTHER));

        assertEquals(2, store.transactions() - before);
        assertEquals(java.util.List.of(BUYER, OTHER), store.lockedBuyers());
    }

    // ------------------------------------------------------------------------------------------------ TC-513: отмены

    @Tag("TC-513")
    @Test
    void cancelFromCreatedWithOutOfStockIsT3AndPublishesOrderCancelled() {
        transitions.place(draft(1, BUYER));

        Optional<StoredOrder> cancelled = transitions.cancel(id(1), CancelReason.OUT_OF_STOCK);

        assertTrue(cancelled.isPresent());
        assertEquals(OrderStatus.CANCELLED, cancelled.get().status());
        assertEquals("out_of_stock", cancelled.get().cancelReason());
        assertEquals(1, store.events().size());
        FakeOrderStore.Published event = store.events().get(0);
        assertEquals("order.cancelled", event.eventType());
        assertEquals("order.events", event.topic());
        assertEquals(id(1).toString(), event.aggregateId());
        assertEquals(Set.of("orderId", "buyerId", "productId", "quantity", "cancelReason"), event.data().keySet());
        assertEquals(BUYER.toString(), event.data().get("buyerId"));
        assertEquals(PRODUCT.toString(), event.data().get("productId"));
        assertEquals(2, event.data().get("quantity"));
        assertEquals("out_of_stock", event.data().get("cancelReason"));
        assertEquals(1.0, meters.find("orders_cancelled_total").tag("reason", "out_of_stock").tag("transition", "T3").counter().count());
    }

    @Tag("TC-513")
    @Test
    void cancelFromCreatedWithASessionFailureOrASystemErrorIsT12() {
        transitions.place(draft(1, BUYER));
        transitions.place(draft(2, BUYER));

        assertTrue(transitions.cancel(id(1), CancelReason.PAYMENT_SESSION_FAILED).isPresent());
        assertTrue(transitions.cancel(id(2), CancelReason.SYSTEM_ERROR).isPresent());

        assertEquals(2, store.events("order.cancelled").size());
        assertEquals(1.0, meters.find("orders_cancelled_total").tag("reason", "payment_session_failed").tag("transition", "T12").counter().count());
        assertEquals(1.0, meters.find("orders_cancelled_total").tag("reason", "system_error").tag("transition", "T12").counter().count());
    }

    @Tag("TC-513")
    @Test
    void cancelFromAwaitingPaymentIsT5ForADeclineAndT6ForAnExpiredReservation() {
        transitions.place(draft(1, BUYER));
        transitions.place(draft(2, BUYER));
        transitions.awaitPayment(id(1), RESERVE_UNTIL, SESSION_UNTIL, URL);
        transitions.awaitPayment(id(2), RESERVE_UNTIL, SESSION_UNTIL, URL);

        assertTrue(transitions.cancel(id(1), CancelReason.PAYMENT_DECLINED).isPresent());
        assertTrue(transitions.cancel(id(2), CancelReason.RESERVATION_EXPIRED).isPresent());

        assertEquals(1.0, meters.find("orders_cancelled_total").tag("transition", "T5").counter().count());
        assertEquals(1.0, meters.find("orders_cancelled_total").tag("transition", "T6").counter().count());
    }

    @Tag("TC-513")
    @Test
    void aReasonThatDoesNotFitTheStatusLeavesTheOrderAndPublishesNothing() {
        transitions.place(draft(1, BUYER));
        transitions.place(draft(2, BUYER));
        transitions.awaitPayment(id(2), RESERVE_UNTIL, SESSION_UNTIL, URL);
        int events = store.events().size();

        assertTrue(transitions.cancel(id(1), CancelReason.PAYMENT_DECLINED).isEmpty(), "из «создан» нельзя по причине отказа шлюза");
        assertTrue(transitions.cancel(id(2), CancelReason.OUT_OF_STOCK).isEmpty(), "из «ожидает оплаты» нельзя по причине нехватки ключей");
        assertTrue(transitions.cancel(id(2), "unknown").isEmpty());
        assertTrue(transitions.cancel(id(2), null).isEmpty());

        assertEquals(OrderStatus.CREATED, store.get(id(1)).status());
        assertEquals(OrderStatus.AWAITING_PAYMENT, store.get(id(2)).status());
        assertEquals(events, store.events().size());
    }

    @Tag("TC-513")
    @Test
    void aRepeatedCancelAndACancelOfAnUnknownOrPaidOrderDoNothing() {
        transitions.place(draft(1, BUYER));
        store.seed(existing(2, BUYER, OrderStatus.PAID));

        assertTrue(transitions.cancel(id(1), CancelReason.SYSTEM_ERROR).isPresent());
        assertTrue(transitions.cancel(id(1), CancelReason.SYSTEM_ERROR).isEmpty());
        assertTrue(transitions.cancel(id(9), CancelReason.SYSTEM_ERROR).isEmpty());
        assertTrue(transitions.cancel(id(2), CancelReason.SYSTEM_ERROR).isEmpty());

        assertEquals(1, store.events("order.cancelled").size(), "повтор второго события не рождает");
        assertEquals(OrderStatus.PAID, store.get(id(2)).status());
    }

    @Tag("TC-513")
    @Test
    void ifTheOutboxIsDownTheTransitionIsRolledBackAsAWhole() {
        transitions.place(draft(1, BUYER));
        store.failOnPublish();

        assertThrows(IllegalStateException.class, () -> transitions.cancel(id(1), CancelReason.SYSTEM_ERROR));

        assertEquals(OrderStatus.CREATED, store.get(id(1)).status(), "статус и событие меняются вместе (ADR-005)");
        assertNull(store.get(id(1)).cancelReason());
        assertEquals(0, store.events().size());
    }

    // ------------------------------------------------------------------------------------------------ TC-516: оплата, T4 и T7

    private static final Instant PAID_AT = T0.plus(Duration.ofMinutes(5));

    /** Заказ, отменённый по причине, которую задал тест. */
    private StoredOrder cancelled(int n, String reason) {
        StoredOrder base = existing(n, BUYER, OrderStatus.CANCELLED);
        StoredOrder order = new StoredOrder(base.id(), base.number(), base.buyerId(), base.sellerId(), base.productId(), base.productTitle(), base.quantity(),
                base.unitPrice(), base.amount(), base.commission(), base.currency(), OrderStatus.CANCELLED, reason, base.deliveryAddress(),
                RESERVE_UNTIL, SESSION_UNTIL, URL, base.createdAt(), null, null);
        store.seed(order);
        return order;
    }

    private double ignored(String transition, String status) {
        var counter = meters.find("orders_transitions_ignored_total").tag("transition", transition).tag("status", status).counter();
        return counter == null ? 0 : counter.count();
    }

    @Tag("TC-516")
    @Test
    void payFromAwaitingPaymentIsT4AndPublishesOrderPaidWithTheAddressInTheSameTransaction() {
        transitions.place(draft(1, BUYER));
        transitions.awaitPayment(id(1), RESERVE_UNTIL, SESSION_UNTIL, URL);
        int before = store.transactions();

        Optional<StoredOrder> paid = transitions.pay(id(1), PAID_AT);

        assertTrue(paid.isPresent());
        assertEquals(1, store.transactions() - before, "переход и событие в одной транзакции");
        assertEquals(OrderStatus.PAID, paid.get().status());
        assertEquals(PAID_AT, paid.get().paidAt());
        assertNull(paid.get().cancelReason());
        FakeOrderStore.Published event = store.events("order.paid").get(0);
        assertEquals("order.events", event.topic());
        assertEquals(id(1).toString(), event.aggregateId());
        assertEquals(1, event.schemaVersion());
        Map<String, Object> data = event.data();
        assertEquals(Set.of("orderId", "orderNumber", "buyerId", "sellerId", "productId", "productTitle", "quantity", "total", "commission", "paidAt",
                "latePayment", "delivery"), data.keySet());
        assertEquals("2026-10-18T12:05:00.123Z", data.get("paidAt"));
        assertEquals(false, data.get("latePayment"));
        assertEquals("Лунная тропа: ключ Steam", data.get("productTitle"));
        assertEquals(Map.of("amount", 299_800L, "currency", "RUB"), data.get("total"));
        assertEquals(Map.of("amount", 5_996L, "currency", "RUB"), data.get("commission"));
        assertEquals(Map.of("channel", "email", "address", "buyer@mail.example"), data.get("delivery"));
        assertEquals(1.0, counter("orders_paid_total", "transition", "T4"));
        assertEquals(0.0, counter("late_payments_total", "result", "delivered"));
    }

    @Tag("TC-516")
    @Test
    void payFromCancelledIsT7LatePaymentAndStartsTheClockAgain() {
        cancelled(1, CancelReason.RESERVATION_EXPIRED);

        Optional<StoredOrder> paid = transitions.pay(id(1), PAID_AT);

        assertTrue(paid.isPresent());
        assertEquals(OrderStatus.PAID, paid.get().status());
        assertNull(paid.get().cancelReason(), "причина отмены не нужна оплаченному заказу");
        assertEquals(PAID_AT, paid.get().paidAt());
        assertEquals(true, store.events("order.paid").get(0).data().get("latePayment"));
        assertEquals(1.0, counter("orders_paid_total", "transition", "T7"));
        assertEquals(1.0, counter("late_payments_total", "result", "delivered"));
    }

    @Tag("TC-516")
    @Test
    void payOfAnyOtherStatusAndOfAnUnknownOrderChangesNothingAndPublishesNothing() {
        transitions.place(draft(1, BUYER));
        store.seed(existing(2, BUYER, OrderStatus.PAID)).seed(existing(3, BUYER, OrderStatus.ISSUED)).seed(existing(4, BUYER, OrderStatus.REFUNDED));

        for (int n = 1; n <= 4; n++) {
            assertTrue(transitions.pay(id(n), PAID_AT).isEmpty(), "заказ " + n);
        }
        assertTrue(transitions.pay(id(9), PAID_AT).isEmpty());

        assertEquals(OrderStatus.CREATED, store.get(id(1)).status(), "из «создан» сразу в «оплачен» нельзя");
        assertEquals(OrderStatus.PAID, store.get(id(2)).status());
        assertEquals(T0, store.get(id(2)).paidAt(), "повтор не двигает момент оплаты");
        assertEquals(OrderStatus.ISSUED, store.get(id(3)).status());
        assertEquals(OrderStatus.REFUNDED, store.get(id(4)).status());
        assertEquals(0, store.events().size());
        assertEquals(1.0, ignored("T4", OrderStatus.CREATED));
        assertEquals(1.0, ignored("T4", OrderStatus.PAID));
        assertEquals(1.0, ignored("T4", OrderStatus.REFUNDED));
    }

    @Tag("TC-516")
    @Test
    void ifTheOutboxIsDownThePaymentIsRolledBackAsAWhole() {
        transitions.place(draft(1, BUYER));
        transitions.awaitPayment(id(1), RESERVE_UNTIL, SESSION_UNTIL, URL);
        store.failOnPublish();

        assertThrows(IllegalStateException.class, () -> transitions.pay(id(1), PAID_AT));

        assertEquals(OrderStatus.AWAITING_PAYMENT, store.get(id(1)).status());
        assertNull(store.get(id(1)).paidAt());
        assertEquals(1, store.events().size(), "остаётся только order.created");
    }

    // ------------------------------------------------------------------------------------------------ TC-517: автовозврат поздней оплаты

    @Tag("TC-517")
    @Test
    void requestRefundOfACancelledOrderPublishesOnlyTheCommandForTheWholeAmount() {
        cancelled(1, CancelReason.RESERVATION_EXPIRED);

        Optional<StoredOrder> order = transitions.requestRefund(id(1), "late_payment");

        assertTrue(order.isPresent());
        assertEquals(OrderStatus.CANCELLED, order.get().status());
        assertEquals(List.of("order.refund-requested"), store.events().stream().map(FakeOrderStore.Published::eventType).toList());
        FakeOrderStore.Published event = store.events().get(0);
        assertEquals(Set.of("orderId", "amount", "refundReason"), event.data().keySet());
        assertEquals(id(1).toString(), event.data().get("orderId"));
        assertEquals(Map.of("amount", 100_00L, "currency", "RUB"), event.data().get("amount"));
        assertEquals("late_payment", event.data().get("refundReason"));
        assertEquals(1.0, counter("late_payments_total", "result", "refunded"));
    }

    @Tag("TC-517")
    @Test
    void requestRefundOfAnAwaitingOrderCancelsItFirstByT6AndThenAsksForTheRefundInOneTransaction() {
        transitions.place(draft(1, BUYER));
        transitions.awaitPayment(id(1), RESERVE_UNTIL, SESSION_UNTIL, URL);
        int events = store.events().size();
        int before = store.transactions();

        Optional<StoredOrder> order = transitions.requestRefund(id(1), "late_payment");

        assertTrue(order.isPresent());
        assertEquals(1, store.transactions() - before);
        assertEquals(OrderStatus.CANCELLED, order.get().status());
        assertEquals(CancelReason.RESERVATION_EXPIRED, order.get().cancelReason());
        assertEquals(List.of("order.cancelled", "order.refund-requested"),
                store.events().subList(events, store.events().size()).stream().map(FakeOrderStore.Published::eventType).toList());
        assertEquals(1.0, meters.find("orders_cancelled_total").tag("transition", "T6").counter().count());
    }

    @Tag("TC-517")
    @Test
    void requestRefundForPaidIssuedRefundedCreatedAndUnknownOrdersPublishesNothing() {
        transitions.place(draft(1, BUYER));
        store.seed(existing(2, BUYER, OrderStatus.PAID)).seed(existing(3, BUYER, OrderStatus.ISSUED)).seed(existing(4, BUYER, OrderStatus.REFUNDED));

        for (int n = 1; n <= 4; n++) {
            assertTrue(transitions.requestRefund(id(n), "late_payment").isEmpty(), "заказ " + n);
        }
        assertTrue(transitions.requestRefund(id(9), "late_payment").isEmpty());

        assertEquals(0, store.events().size());
        assertEquals(0.0, counter("late_payments_total", "result", "refunded"));
        assertEquals(1.0, ignored("refund", OrderStatus.PAID));
        assertEquals(1.0, ignored("refund", OrderStatus.CREATED));
    }

    @Tag("TC-517")
    @Test
    void ifTheOutboxIsDownTheCancelAndTheRefundCommandAreRolledBackTogether() {
        transitions.place(draft(1, BUYER));
        transitions.awaitPayment(id(1), RESERVE_UNTIL, SESSION_UNTIL, URL);
        store.failOnPublish();

        assertThrows(IllegalStateException.class, () -> transitions.requestRefund(id(1), "late_payment"));

        assertEquals(OrderStatus.AWAITING_PAYMENT, store.get(id(1)).status());
        assertEquals(1, store.events().size());
    }

    // ------------------------------------------------------------------------------------------------ TC-518: T8, T9, обезличивание

    @Tag("TC-518")
    @Test
    void refundOfACancelledOrderIsT8AndPublishesOrderRefunded() {
        cancelled(1, CancelReason.RESERVATION_EXPIRED);

        Optional<StoredOrder> refunded = transitions.refund(id(1), "late_payment");

        assertTrue(refunded.isPresent());
        assertEquals(OrderStatus.REFUNDED, refunded.get().status());
        FakeOrderStore.Published event = store.events("order.refunded").get(0);
        assertEquals(Set.of("orderId", "buyerId", "sellerId", "total", "refundReason"), event.data().keySet());
        assertEquals(Map.of("amount", 100_00L, "currency", "RUB"), event.data().get("total"));
        assertEquals("late_payment", event.data().get("refundReason"));
        assertFalse(event.data().toString().contains("buyer@mail.example"));
        assertEquals(1.0, counter("orders_refunded_total", "reason", "late_payment"));
    }

    @Tag("TC-518")
    @Test
    void refundOfAnyOtherStatusAndOfAnUnknownOrderChangesNothing() {
        transitions.place(draft(1, BUYER));
        store.seed(existing(2, BUYER, OrderStatus.PAID)).seed(existing(3, BUYER, OrderStatus.ISSUED)).seed(existing(4, BUYER, OrderStatus.REFUNDED));

        for (int n = 1; n <= 4; n++) {
            assertTrue(transitions.refund(id(n), "late_payment").isEmpty(), "заказ " + n);
        }
        assertTrue(transitions.refund(id(9), "late_payment").isEmpty());

        assertEquals(0, store.events().size());
        assertEquals(OrderStatus.ISSUED, store.get(id(3)).status(), "T11 (выдан в возвращён) относится к R2 и не выполняется");
        assertEquals(1.0, ignored("T8", OrderStatus.ISSUED));
    }

    @Tag("TC-518")
    @Test
    void issueOfAPaidOrderIsT9FixesTheMomentOnceAndPublishesOrderIssued() {
        store.seed(existing(1, BUYER, OrderStatus.PAID));
        UUID delivery = UUID.fromString("0199e0a0-0000-7000-8000-000000000501");
        Instant issuedAt = T0.plus(Duration.ofMinutes(7));

        Optional<StoredOrder> issued = transitions.issue(id(1), delivery, issuedAt);

        assertTrue(issued.isPresent());
        assertEquals(OrderStatus.ISSUED, issued.get().status());
        assertEquals(issuedAt, issued.get().issuedAt());
        FakeOrderStore.Published event = store.events("order.issued").get(0);
        assertEquals(Set.of("orderId", "orderNumber", "buyerId", "sellerId", "productId", "total", "commission", "deliveryId", "issuedAt"),
                event.data().keySet());
        assertEquals(delivery.toString(), event.data().get("deliveryId"));
        assertEquals("2026-10-18T12:07:00.123Z", event.data().get("issuedAt"));
        assertEquals(1.0, meters.counter("orders_issued_total").count());

        assertTrue(transitions.issue(id(1), delivery, issuedAt.plusSeconds(60)).isEmpty(), "повторная выдача ничего не меняет (INV-17)");
        assertEquals(issuedAt, store.get(id(1)).issuedAt());
        assertEquals(1, store.events("order.issued").size());
    }

    @Tag("TC-518")
    @Test
    void issueOfAnyOtherStatusAndOfAnUnknownOrderChangesNothing() {
        transitions.place(draft(1, BUYER));
        store.seed(existing(2, BUYER, OrderStatus.REFUNDED));
        cancelled(3, CancelReason.PAYMENT_DECLINED);
        UUID delivery = UUID.fromString("0199e0a0-0000-7000-8000-000000000501");

        for (int n = 1; n <= 3; n++) {
            assertTrue(transitions.issue(id(n), delivery, PAID_AT).isEmpty(), "заказ " + n);
        }
        assertTrue(transitions.issue(id(9), delivery, PAID_AT).isEmpty());

        assertEquals(0, store.events().size());
        assertEquals(OrderStatus.CANCELLED, store.get(id(3)).status());
    }

    @Tag("TC-518")
    @Test
    void anonymizeReplacesTheAddressInAllOrdersOfTheBuyerOnlyAndKeepsTheStatuses() {
        transitions.place(draft(1, BUYER));
        store.seed(existing(2, BUYER, OrderStatus.ISSUED));
        transitions.place(draft(3, OTHER));

        int changed = transitions.anonymize(BUYER);

        assertEquals(2, changed);
        assertEquals(OrderTransitions.ANONYMIZED_ADDRESS, store.get(id(1)).deliveryAddress());
        assertEquals(OrderTransitions.ANONYMIZED_ADDRESS, store.get(id(2)).deliveryAddress());
        assertEquals("buyer@mail.example", store.get(id(3)).deliveryAddress());
        assertEquals(OrderStatus.CREATED, store.get(id(1)).status());
        assertEquals(OrderStatus.ISSUED, store.get(id(2)).status());
        assertEquals(0, store.events().size(), "события об обезличивании нет: аудит ведёт «Платформа»");
        assertEquals(2.0, meters.counter("orders_anonymized_total").count());
    }

    @Tag("TC-518")
    @Test
    void anonymizeTwiceAndForABuyerWithoutOrdersChangesNothing() {
        transitions.place(draft(1, BUYER));

        assertEquals(1, transitions.anonymize(BUYER));
        assertEquals(0, transitions.anonymize(BUYER));
        assertEquals(0, transitions.anonymize(UUID.fromString("0199e0a0-0000-4000-8000-0000000000ff")));
        assertEquals(1.0, meters.counter("orders_anonymized_total").count());
    }

    @Tag("TC-518")
    @Test
    void theAnonymizedAddressFitsTheLimitsOfTheDatabaseAndIsNotMailable() {
        String address = OrderTransitions.ANONYMIZED_ADDRESS;

        assertTrue(address.length() >= 3 && address.length() <= 254, "ограничение длины delivery_address в базе");
        assertTrue(address.endsWith(".invalid"), "зона .invalid зарезервирована: письмо туда не уйдёт");
    }

    @Tag("TC-518")
    @Test
    void findReturnsTheOrderWithoutChangingItAndEmptyForAnUnknownId() {
        transitions.place(draft(1, BUYER));
        int before = store.transactions();

        assertEquals(OrderStatus.CREATED, transitions.find(id(1)).orElseThrow().status());
        assertTrue(transitions.find(id(9)).isEmpty());

        assertEquals(2, store.transactions() - before);
        assertEquals(0, store.events().size());
    }
}
