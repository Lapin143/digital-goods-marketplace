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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Переходы заказа в одной короткой транзакции: T1 под блокировкой покупателя с лимитом неоплаченных (Р-142), T2 с событием
 * {@code order.created}, отмены T3, T5, T6, T12 с событием {@code order.cancelled}. Повтор и запоздавший вызов ничего не меняют.
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
}
