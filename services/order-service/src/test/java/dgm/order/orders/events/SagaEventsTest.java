package dgm.order.orders.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.events.DlqSink;
import dgm.kit.events.EventProcessor;
import dgm.kit.json.Json;
import dgm.kit.time.ManualClock;
import dgm.order.orders.anonymize.UserAnonymizedHandler;
import dgm.order.orders.client.CatalogClient;
import dgm.order.orders.client.InventoryClient;
import dgm.order.orders.client.InventoryClient.ConfirmResult;
import dgm.order.orders.client.InventoryClient.Confirmed;
import dgm.order.orders.client.InventoryClient.NoKeys;
import dgm.order.orders.client.PaymentClient;
import dgm.order.orders.domain.CancelReason;
import dgm.order.orders.domain.OrderStatus;
import dgm.order.orders.domain.StoredOrder;
import dgm.order.orders.service.FakeOrderStore;
import dgm.order.orders.service.FakeOrderStore.Published;
import dgm.order.orders.service.OrderSettings;
import dgm.order.orders.service.OrderTransitions;
import dgm.order.orders.service.SagaOrchestrator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * События саги заказа через настоящий каркас потребителя ({@link EventProcessor}) на подмене базы, Outbox и сервиса остатков: оплата (T4 и
 * поздняя оплата T7), отказ шлюза (T5), истечение резерва (T6), возврат (T8), выдача (T9), обезличивание. Проверяется то, что обещают
 * ADR-004 и ADR-013: порядок «решение и вызов соседа вне транзакции, потом переход, Outbox и отметка обработанного одной транзакцией», повтор
 * и перестановка событий не меняют итог, неисправимое событие уходит в DLQ без повторов.
 */
class SagaEventsTest {

    private static final Instant T0 = Instant.parse("2026-10-18T12:00:00.123Z");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID OTHER_BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final String EMAIL = "buyer@mail.example";
    private static final String PAYMENTS = "payment.events";
    private static final String INVENTORY = "inventory.events";
    private static final String DELIVERY = "delivery.events";
    private static final String IDENTITY = "identity.events";

    private FakeOrderStore store;
    private ManualClock clock;
    private SimpleMeterRegistry meters;
    private Inventory inventory;
    private EventProcessor processor;
    private final List<ProducerRecord<String, String>> dlq = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private Runnable onSleep = () -> {
    };
    private long offset;

    /** Заглушка сервиса остатков: подтверждение резерва; вызов внутри транзакции это нарушение правила 9. */
    private final class Inventory implements InventoryClient {
        ConfirmResult result = new Confirmed(UUID.randomUUID(), false);
        RuntimeException failure;
        final List<UUID> confirmed = new ArrayList<>();

        @Override
        public ReserveResult reserve(UUID orderId, UUID productId, int quantity) {
            throw new UnsupportedOperationException("Резерв при оформлении проверяет SagaOrchestratorTest");
        }

        @Override
        public ConfirmResult confirm(UUID orderId, UUID productId, int quantity) {
            if (store.inTransaction()) {
                throw new IllegalStateException("Сосед вызван внутри транзакции (правило 9)");
            }
            assertEquals(PRODUCT, productId);
            assertEquals(2, quantity);
            confirmed.add(orderId);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    @BeforeEach
    void setUp() {
        store = new FakeOrderStore();
        clock = new ManualClock(T0);
        meters = new SimpleMeterRegistry();
        inventory = new Inventory();
        OrderSettings settings = new OrderSettings(3, Duration.ofMinutes(15), Duration.ofMinutes(12), 200);
        OrderTransitions transitions = new OrderTransitions(store, store, settings, meters);
        CatalogClient catalog = productId -> Optional.empty();
        PaymentClient payments = (orderId, amount, currency, sessionExpiresAt) -> {
            throw new UnsupportedOperationException("Платёжную сессию проверяет SagaOrchestratorTest");
        };
        SagaOrchestrator saga = new SagaOrchestrator(catalog, inventory, payments, transitions, settings, clock, meters);
        DlqSink sink = dlq::add;
        processor = new EventProcessor("order-service",
                List.of(new PaymentConfirmedHandler(saga, clock), new PaymentResultHandler(saga), new ReservationExpiredHandler(saga),
                        new DeliveryAcceptedHandler(saga), new UserAnonymizedHandler(transitions)),
                store.processedEvents(), store.operations(), sink, EventProcessor.DEFAULT_RETRY_DELAYS, clock, duration -> {
                    sleeps.add(duration);
                    onSleep.run();
                }, meters);
    }

    // ------------------------------------------------------------------------------------------------ данные

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 300 + n));
    }

    private StoredOrder seed(int n, UUID buyer, String status, String cancelReason) {
        boolean awaiting = OrderStatus.AWAITING_PAYMENT.equals(status);
        boolean paid = OrderStatus.PAID.equals(status) || OrderStatus.ISSUED.equals(status);
        StoredOrder order = new StoredOrder(id(n), 1000L + n, buyer, SELLER, PRODUCT, "Лунная тропа: ключ Steam", 2, 149_900, 299_800, 5_996, "RUB",
                status, cancelReason, EMAIL, awaiting ? T0.plus(Duration.ofMinutes(15)) : null, awaiting ? T0.plus(Duration.ofMinutes(12)) : null,
                awaiting ? "https://localhost:8443/payment/pay/pay_1" : null, T0.minus(Duration.ofMinutes(5)), paid ? T0.minus(Duration.ofMinutes(1)) : null,
                OrderStatus.ISSUED.equals(status) ? T0.minus(Duration.ofSeconds(30)) : null);
        store.seed(order);
        return order;
    }

    private StoredOrder seed(int n, String status, String cancelReason) {
        return seed(n, BUYER, status, cancelReason);
    }

    private static Map<String, Object> money() {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", 299_800);
        money.put("currency", "RUB");
        return money;
    }

    private static Map<String, Object> confirmedData(UUID orderId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", UUID.randomUUID().toString());
        data.put("orderId", orderId.toString());
        data.put("amount", money());
        data.put("confirmedAt", "2026-10-18T12:00:00.000Z");
        return data;
    }

    private static Map<String, Object> rejectedData(UUID orderId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", UUID.randomUUID().toString());
        data.put("orderId", orderId.toString());
        data.put("reason", "declined");
        return data;
    }

    private static Map<String, Object> refundedData(UUID orderId, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", UUID.randomUUID().toString());
        data.put("orderId", orderId.toString());
        data.put("amount", money());
        data.put("refundReason", reason);
        data.put("manual", false);
        data.put("refundedAt", "2026-10-18T12:01:00.000Z");
        return data;
    }

    private static Map<String, Object> expiredData(UUID orderId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reservationId", UUID.randomUUID().toString());
        data.put("orderId", orderId.toString());
        data.put("productId", PRODUCT.toString());
        data.put("quantity", 2);
        data.put("expiredBy", "timer");
        return data;
    }

    private static Map<String, Object> acceptedData(UUID orderId, UUID deliveryId, String type, String acceptedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", deliveryId.toString());
        data.put("orderId", orderId.toString());
        data.put("deliveryType", type);
        data.put("attemptNo", 1);
        data.put("acceptedAt", acceptedAt);
        return data;
    }

    private static Map<String, Object> anonymizedData(String userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("requestedBy", "user");
        return data;
    }

    private ConsumerRecord<String, String> record(String topic, String type, UUID eventId, UUID subject, Map<String, Object> data) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("specversion", "1.0");
        doc.put("id", eventId.toString());
        doc.put("source", "test-service");
        doc.put("type", type);
        doc.put("time", "2026-10-18T12:00:00.123Z");
        doc.put("subject", subject.toString());
        doc.put("datacontenttype", "application/json");
        doc.put("schemaversion", 1);
        doc.put("aggregatetype", "Test");
        doc.put("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        doc.put("correlationid", "6b3c0a1e-1111-4111-8111-111111111111");
        doc.put("data", data);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, offset++, subject.toString(), Json.write(doc));
        record.headers().add("ce_type", type.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private void deliver(String topic, String type, UUID eventId, UUID subject, Map<String, Object> data) throws Exception {
        processor.process(record(topic, type, eventId, subject, data));
    }

    /** Новое событие: свой идентификатор. */
    private UUID confirm(UUID orderId) throws Exception {
        UUID eventId = UUID.randomUUID();
        deliver(PAYMENTS, "payment.confirmed", eventId, orderId, confirmedData(orderId));
        return eventId;
    }

    private void expire(UUID orderId) throws Exception {
        deliver(INVENTORY, "reservation.expired", UUID.randomUUID(), orderId, expiredData(orderId));
    }

    private String status(UUID orderId) {
        return store.get(orderId).status();
    }

    private double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double processedCount(String result) {
        return count("events_processed_total", "result", result);
    }

    private List<String> eventTypes() {
        return store.events().stream().map(Published::eventType).toList();
    }

    // ------------------------------------------------------------------------------------------------ TC-516: оплата

    @Tag("TC-516")
    @Test
    void confirmedPaymentOfAwaitingOrderPaysItAndPublishesOrderPaid() throws Exception {
        StoredOrder order = seed(1, OrderStatus.AWAITING_PAYMENT, null);
        confirm(order.id());

        StoredOrder paid = store.get(order.id());
        assertEquals(OrderStatus.PAID, paid.status());
        assertEquals(T0, paid.paidAt(), "момент оплаты это момент, когда заказ стал оплаченным");
        assertEquals(List.of(order.id()), inventory.confirmed);
        assertEquals(List.of("order.paid"), eventTypes());
        Published event = store.events("order.paid").get(0);
        assertEquals("order.events", event.topic());
        assertEquals("Order", event.aggregateType());
        assertEquals(order.id().toString(), event.aggregateId());
        Map<String, Object> data = event.data();
        assertEquals(order.id().toString(), data.get("orderId"));
        assertEquals(1_001L, ((Number) data.get("orderNumber")).longValue());
        assertEquals(BUYER.toString(), data.get("buyerId"));
        assertEquals(SELLER.toString(), data.get("sellerId"));
        assertEquals(PRODUCT.toString(), data.get("productId"));
        assertEquals("Лунная тропа: ключ Steam", data.get("productTitle"));
        assertEquals(2, data.get("quantity"));
        assertEquals(Map.of("amount", 299_800L, "currency", "RUB"), data.get("total"));
        assertEquals(Map.of("amount", 5_996L, "currency", "RUB"), data.get("commission"));
        assertEquals("2026-10-18T12:00:00.123Z", data.get("paidAt"));
        assertEquals(false, data.get("latePayment"));
        assertEquals(Map.of("channel", "email", "address", EMAIL), data.get("delivery"));
        assertEquals(1, processedCount("applied"));
        assertEquals(1, store.processedCount());
        assertEquals(1, count("orders_paid_total", "transition", "T4"));
        assertTrue(dlq.isEmpty());
    }

    @Tag("TC-516")
    @Test
    void confirmationThatNeededNewReservationIsStillAnOrdinaryPayment() throws Exception {
        // резерв успел сняться, но заказ ещё «ожидает оплаты»: ADR-013, это обычная оплата, не поздняя
        StoredOrder order = seed(1, OrderStatus.AWAITING_PAYMENT, null);
        inventory.result = new Confirmed(UUID.randomUUID(), true);
        confirm(order.id());

        assertEquals(OrderStatus.PAID, status(order.id()));
        assertEquals(false, store.events("order.paid").get(0).data().get("latePayment"));
        assertEquals(0, count("late_payments_total", "result", "delivered"));
    }

    @Tag("TC-516")
    @Test
    void sameEventDeliveredTwiceIsAppliedOnceAndNeighbourIsNotCalledAgain() throws Exception {
        StoredOrder order = seed(1, OrderStatus.AWAITING_PAYMENT, null);
        UUID eventId = UUID.randomUUID();
        ConsumerRecord<String, String> record = record(PAYMENTS, "payment.confirmed", eventId, order.id(), confirmedData(order.id()));
        processor.process(record);
        processor.process(record);

        assertEquals(1, inventory.confirmed.size(), "дубль отсеян до вызова соседа");
        assertEquals(1, store.events("order.paid").size());
        assertEquals(1, processedCount("applied"));
        assertEquals(1, processedCount("duplicate"));
        assertEquals(1, store.processedCount());
    }

    @Tag("TC-516")
    @Test
    void anotherConfirmationOfPaidOrderIsIgnoredWithoutCallingTheNeighbour() throws Exception {
        StoredOrder order = seed(1, OrderStatus.AWAITING_PAYMENT, null);
        confirm(order.id());
        inventory.confirmed.clear();
        confirm(order.id());

        assertTrue(inventory.confirmed.isEmpty());
        assertEquals(List.of("order.paid"), eventTypes());
        assertEquals(1, count("events_ignored_total", "reason", "payment-already-applied"));
        assertEquals(1, count("payment_duplicates_total"));
        assertEquals(2, store.processedCount(), "второе событие тоже обработано: его идентификатор записан");
    }

    @Tag("TC-516")
    @Test
    void failureWhilePublishingRollsBackTheTransitionAndTheMark() throws Exception {
        StoredOrder order = seed(1, OrderStatus.AWAITING_PAYMENT, null);
        store.failOnPublish();
        confirm(order.id());

        assertEquals(OrderStatus.AWAITING_PAYMENT, status(order.id()), "переход откатился вместе с Outbox");
        assertEquals(0, store.processedCount(), "отметка обработанного откатилась вместе с ними");
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(25)), sleeps);
        assertEquals(4, inventory.confirmed.size(), "каждый повтор снова подтверждает резерв: вызов идемпотентен на стороне остатков");
        assertEquals(1, dlq.size());
        assertEquals("payment.events.dlq", dlq.get(0).topic());
    }

    // ------------------------------------------------------------------------------------------------ TC-517: поздняя оплата

    @Tag("TC-517")
    @Test
    void lateConfirmationWithKeysPaysCancelledOrderAndMarksLatePayment() throws Exception {
        StoredOrder order = seed(1, OrderStatus.CANCELLED, CancelReason.RESERVATION_EXPIRED);
        inventory.result = new Confirmed(UUID.randomUUID(), true);
        confirm(order.id());

        StoredOrder paid = store.get(order.id());
        assertEquals(OrderStatus.PAID, paid.status(), "SM-01/T7");
        assertNull(paid.cancelReason(), "заказ больше не отменён");
        assertEquals(T0, paid.paidAt(), "контроль выдачи 30 минут запускается заново");
        assertEquals(List.of("order.paid"), eventTypes());
        assertEquals(true, store.events("order.paid").get(0).data().get("latePayment"));
        assertEquals(1, count("orders_paid_total", "transition", "T7"));
        assertEquals(1, count("late_payments_total", "result", "delivered"));
    }

    @Tag("TC-517")
    @Test
    void lateConfirmationWithoutKeysKeepsOrderCancelledAndRequestsFullRefund() throws Exception {
        StoredOrder order = seed(1, OrderStatus.CANCELLED, CancelReason.RESERVATION_EXPIRED);
        inventory.result = new NoKeys(1);
        confirm(order.id());

        assertEquals(OrderStatus.CANCELLED, status(order.id()), "статус не меняется (E7)");
        assertEquals(List.of("order.refund-requested"), eventTypes());
        Map<String, Object> data = store.events("order.refund-requested").get(0).data();
        assertEquals(order.id().toString(), data.get("orderId"));
        assertEquals(Map.of("amount", 299_800L, "currency", "RUB"), data.get("amount"), "возврат всегда на всю сумму заказа");
        assertEquals("late_payment", data.get("refundReason"));
        assertEquals(1, count("late_payments_total", "result", "refunded"));

        // payment-service вернул деньги: T8 и событие order.refunded
        deliver(PAYMENTS, "payment.refunded", UUID.randomUUID(), order.id(), refundedData(order.id(), "late_payment"));
        assertEquals(OrderStatus.REFUNDED, status(order.id()));
        assertEquals(List.of("order.refund-requested", "order.refunded"), eventTypes());
        Map<String, Object> refunded = store.events("order.refunded").get(0).data();
        assertEquals(BUYER.toString(), refunded.get("buyerId"));
        assertEquals(SELLER.toString(), refunded.get("sellerId"));
        assertEquals("late_payment", refunded.get("refundReason"));
        assertEquals(Map.of("amount", 299_800L, "currency", "RUB"), refunded.get("total"));
    }

    @Tag("TC-517")
    @Test
    void confirmationWithoutKeysForAwaitingOrderCancelsItThenRequestsRefundInOneTransaction() throws Exception {
        StoredOrder order = seed(1, OrderStatus.AWAITING_PAYMENT, null);
        inventory.result = new NoKeys(0);
        confirm(order.id());

        StoredOrder cancelled = store.get(order.id());
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(CancelReason.RESERVATION_EXPIRED, cancelled.cancelReason(), "SM-01/T6");
        assertEquals(List.of("order.cancelled", "order.refund-requested"), eventTypes());
        assertEquals(2, store.transactions(), "чтение статуса перед вызовом соседа и одна транзакция записи: отмена, команда возврата и отметка вместе");
    }

    @Tag("TC-517")
    @Test
    void refundedPaymentAfterRefundedOrderIsIgnoredAndOtherStatusesAreNotRefunded() throws Exception {
        StoredOrder cancelled = seed(1, OrderStatus.CANCELLED, CancelReason.RESERVATION_EXPIRED);
        StoredOrder paid = seed(2, OrderStatus.PAID, null);
        deliver(PAYMENTS, "payment.refunded", UUID.randomUUID(), cancelled.id(), refundedData(cancelled.id(), "late_payment"));
        deliver(PAYMENTS, "payment.refunded", UUID.randomUUID(), cancelled.id(), refundedData(cancelled.id(), "late_payment"));
        deliver(PAYMENTS, "payment.refunded", UUID.randomUUID(), paid.id(), refundedData(paid.id(), "late_payment"));

        assertEquals(OrderStatus.REFUNDED, status(cancelled.id()));
        assertEquals(OrderStatus.PAID, status(paid.id()), "в R1 оплаченный заказ не возвращается (T11 это R2)");
        assertEquals(List.of("order.refunded"), eventTypes());
        assertEquals(2, count("events_ignored_total", "reason", "order-not-cancelled"));
    }

    @Tag("TC-517")
    @Test
    void confirmationForOrderCancelledByDeclinedPaymentIsAnAnomalyAndChangesNothing() throws Exception {
        StoredOrder order = seed(1, OrderStatus.CANCELLED, CancelReason.PAYMENT_DECLINED);
        confirm(order.id());

        assertEquals(OrderStatus.CANCELLED, status(order.id()));
        assertTrue(inventory.confirmed.isEmpty(), "ключи для отклонённого платежа не закрепляются");
        assertTrue(store.events().isEmpty());
        assertEquals(1, count("payment_anomalies_total", "kind", "payment-declined-order"));
        assertEquals(1, count("events_ignored_total", "reason", "payment-declined-order"));
    }

    @Tag("TC-517")
    @Test
    void confirmationForRefundedUnknownAndIssuedOrdersChangesNothing() throws Exception {
        StoredOrder refunded = seed(1, OrderStatus.REFUNDED, CancelReason.RESERVATION_EXPIRED);
        StoredOrder issued = seed(2, OrderStatus.ISSUED, null);
        confirm(refunded.id());
        confirm(issued.id());
        confirm(id(99));

        assertEquals(OrderStatus.REFUNDED, status(refunded.id()));
        assertEquals(OrderStatus.ISSUED, status(issued.id()));
        assertTrue(inventory.confirmed.isEmpty());
        assertTrue(store.events().isEmpty());
        assertEquals(1, count("payment_anomalies_total", "kind", "order-refunded"));
        assertEquals(1, count("payment_anomalies_total", "kind", "unknown-order"));
        assertEquals(1, count("payment_duplicates_total"));
        assertTrue(dlq.isEmpty());
    }

    @Tag("TC-517")
    @Test
    void inventoryOutageIsRetriedAndThePaymentIsAppliedWhenItReturns() throws Exception {
        StoredOrder order = seed(1, OrderStatus.AWAITING_PAYMENT, null);
        inventory.failure = new DependencyUnavailableException("inventory-service", DependencyUnavailableException.SERVER_ERROR, 503, null);
        onSleep = () -> inventory.failure = null;
        confirm(order.id());

        assertEquals(List.of(Duration.ofSeconds(1)), sleeps);
        assertEquals(OrderStatus.PAID, status(order.id()));
        assertEquals(2, inventory.confirmed.size());
        assertEquals(List.of("order.paid"), eventTypes());
        assertTrue(dlq.isEmpty());
        assertEquals(1, store.processedCount());
    }

    @Tag("TC-517")
    @Test
    void inventoryOutageBeyondRetriesGoesToDlqAndLeavesOrderAndMarkUntouched() throws Exception {
        StoredOrder order = seed(1, OrderStatus.AWAITING_PAYMENT, null);
        inventory.failure = new DependencyUnavailableException("inventory-service", DependencyUnavailableException.SERVER_ERROR, 503, null);
        confirm(order.id());

        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(25)), sleeps);
        assertEquals(1, dlq.size());
        assertEquals("payment.events.dlq", dlq.get(0).topic());
        assertEquals(OrderStatus.AWAITING_PAYMENT, status(order.id()), "платёж подтверждён, а заказ ждёт разбора из DLQ (ADR-004, риски)");
        assertEquals(0, store.processedCount());
        assertTrue(store.events().isEmpty());
    }

    @Tag("TC-517")
    @Test
    void confirmationAheadOfOrderCreationWaitsUntilTheOrderAwaitsPayment() throws Exception {
        StoredOrder order = seed(1, OrderStatus.CREATED, null);
        onSleep = () -> {
            StoredOrder created = store.get(order.id());
            store.seed(new StoredOrder(created.id(), created.number(), created.buyerId(), created.sellerId(), created.productId(), created.productTitle(),
                    created.quantity(), created.unitPrice(), created.amount(), created.commission(), created.currency(), OrderStatus.AWAITING_PAYMENT, null,
                    created.deliveryAddress(), T0.plus(Duration.ofMinutes(15)), T0.plus(Duration.ofMinutes(12)), "https://localhost:8443/payment/pay/pay_1",
                    created.createdAt(), null, null));
        };
        confirm(order.id());

        assertEquals(List.of(Duration.ofSeconds(1)), sleeps);
        assertEquals(OrderStatus.PAID, status(order.id()));
        assertEquals(1, inventory.confirmed.size(), "пока заказ «создан», сосед не вызывается");
    }

    // ------------------------------------------------------------------------------------------------ TC-518: остальные события

    @Tag("TC-518")
    @Test
    void rejectedPaymentCancelsAwaitingOrderOnlyAndPublishesOrderCancelled() throws Exception {
        StoredOrder awaiting = seed(1, OrderStatus.AWAITING_PAYMENT, null);
        StoredOrder paid = seed(2, OrderStatus.PAID, null);
        deliver(PAYMENTS, "payment.rejected", UUID.randomUUID(), awaiting.id(), rejectedData(awaiting.id()));
        deliver(PAYMENTS, "payment.rejected", UUID.randomUUID(), paid.id(), rejectedData(paid.id()));

        StoredOrder cancelled = store.get(awaiting.id());
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(CancelReason.PAYMENT_DECLINED, cancelled.cancelReason(), "SM-01/T5");
        assertEquals(OrderStatus.PAID, status(paid.id()));
        assertEquals(List.of("order.cancelled"), eventTypes());
        Map<String, Object> data = store.events("order.cancelled").get(0).data();
        assertEquals("payment_declined", data.get("cancelReason"));
        assertEquals(PRODUCT.toString(), data.get("productId"));
        assertEquals(2, data.get("quantity"));
        assertEquals(1, count("events_ignored_total", "reason", "order-not-awaiting-payment"));
    }

    @Tag("TC-518")
    @Test
    void rejectedPaymentAheadOfOrderCreationIsRetriedThenCancels() throws Exception {
        StoredOrder order = seed(1, OrderStatus.CREATED, null);
        onSleep = () -> {
            StoredOrder created = store.get(order.id());
            store.seed(new StoredOrder(created.id(), created.number(), created.buyerId(), created.sellerId(), created.productId(), created.productTitle(),
                    created.quantity(), created.unitPrice(), created.amount(), created.commission(), created.currency(), OrderStatus.AWAITING_PAYMENT, null,
                    created.deliveryAddress(), T0.plus(Duration.ofMinutes(15)), T0.plus(Duration.ofMinutes(12)), "https://localhost:8443/payment/pay/pay_1",
                    created.createdAt(), null, null));
        };
        deliver(PAYMENTS, "payment.rejected", UUID.randomUUID(), order.id(), rejectedData(order.id()));

        assertEquals(List.of(Duration.ofSeconds(1)), sleeps);
        assertEquals(OrderStatus.CANCELLED, status(order.id()));
        assertEquals(CancelReason.PAYMENT_DECLINED, store.get(order.id()).cancelReason());
    }

    @Tag("TC-518")
    @Test
    void expiredReservationCancelsAwaitingOrderOnly() throws Exception {
        StoredOrder awaiting = seed(1, OrderStatus.AWAITING_PAYMENT, null);
        StoredOrder paid = seed(2, OrderStatus.PAID, null);
        StoredOrder cancelled = seed(3, OrderStatus.CANCELLED, CancelReason.OUT_OF_STOCK);
        StoredOrder created = seed(4, OrderStatus.CREATED, null);
        expire(awaiting.id());
        expire(paid.id());
        expire(cancelled.id());
        expire(created.id());
        expire(id(99));

        assertEquals(OrderStatus.CANCELLED, status(awaiting.id()));
        assertEquals(CancelReason.RESERVATION_EXPIRED, store.get(awaiting.id()).cancelReason(), "SM-01/T6");
        assertEquals(OrderStatus.PAID, status(paid.id()));
        assertEquals(CancelReason.OUT_OF_STOCK, store.get(cancelled.id()).cancelReason(), "причина прежней отмены не перезаписывается");
        assertEquals(OrderStatus.CREATED, status(created.id()));
        assertEquals(List.of("order.cancelled"), eventTypes());
        assertEquals(4, count("events_ignored_total", "reason", "order-not-awaiting-payment"));
    }

    @Tag("TC-518")
    @Test
    void acceptedDeliveryIssuesPaidOrderOnceInItsLifetime() throws Exception {
        StoredOrder paid = seed(1, OrderStatus.PAID, null);
        StoredOrder awaiting = seed(2, OrderStatus.AWAITING_PAYMENT, null);
        UUID delivery = UUID.fromString("0199e0a0-0000-7000-8000-000000000801");
        deliver(DELIVERY, "delivery.accepted", UUID.randomUUID(), paid.id(), acceptedData(paid.id(), delivery, "primary", "2026-10-18T12:02:00.456Z"));
        deliver(DELIVERY, "delivery.accepted", UUID.randomUUID(), paid.id(), acceptedData(paid.id(), UUID.randomUUID(), "repeat", "2026-10-18T13:00:00.000Z"));
        deliver(DELIVERY, "delivery.accepted", UUID.randomUUID(), awaiting.id(), acceptedData(awaiting.id(), UUID.randomUUID(), "primary", "2026-10-18T12:02:00.000Z"));

        StoredOrder issued = store.get(paid.id());
        assertEquals(OrderStatus.ISSUED, issued.status());
        assertEquals(Instant.parse("2026-10-18T12:02:00.456Z"), issued.issuedAt(), "момент первой выдачи не сдвигается повторной отправкой (INV-17)");
        assertEquals(OrderStatus.AWAITING_PAYMENT, status(awaiting.id()));
        assertEquals(List.of("order.issued"), eventTypes());
        Map<String, Object> data = store.events("order.issued").get(0).data();
        assertEquals(delivery.toString(), data.get("deliveryId"));
        assertEquals("2026-10-18T12:02:00.456Z", data.get("issuedAt"));
        assertEquals(Map.of("amount", 299_800L, "currency", "RUB"), data.get("total"));
        assertEquals(Map.of("amount", 5_996L, "currency", "RUB"), data.get("commission"));
        assertEquals(2, count("events_ignored_total", "reason", "order-not-paid"));
    }

    @Tag("TC-518")
    @Test
    void anonymizedUserLosesAddressInAllOrdersAndOtherBuyersKeepTheirs() throws Exception {
        StoredOrder first = seed(1, BUYER, OrderStatus.ISSUED, null);
        StoredOrder second = seed(2, BUYER, OrderStatus.CANCELLED, CancelReason.PAYMENT_DECLINED);
        StoredOrder other = seed(3, OTHER_BUYER, OrderStatus.PAID, null);
        UUID first1 = UUID.randomUUID();
        deliver(IDENTITY, "user.anonymized", first1, BUYER, anonymizedData(BUYER.toString()));
        deliver(IDENTITY, "user.anonymized", UUID.randomUUID(), BUYER, anonymizedData(BUYER.toString()));

        assertEquals(OrderTransitions.ANONYMIZED_ADDRESS, store.get(first.id()).deliveryAddress());
        assertEquals(OrderTransitions.ANONYMIZED_ADDRESS, store.get(second.id()).deliveryAddress());
        assertEquals(EMAIL, store.get(other.id()).deliveryAddress());
        assertEquals(OrderStatus.ISSUED, status(first.id()), "статусы, суммы и история остаются");
        assertEquals(OrderStatus.CANCELLED, status(second.id()));
        assertTrue(store.events().isEmpty(), "события обезличивание не издаёт");
        assertEquals(2, count("orders_anonymized_total"));
        assertEquals(1, count("events_ignored_total", "reason", "no-orders-to-anonymize"));
        assertTrue(OrderTransitions.ANONYMIZED_ADDRESS.length() >= 3 && OrderTransitions.ANONYMIZED_ADDRESS.length() <= 254, "ограничение длины в базе");
        assertTrue(OrderTransitions.ANONYMIZED_ADDRESS.endsWith(".invalid"), "письмо на такой адрес не уйдёт");
    }

    @Tag("TC-518")
    @Test
    void eventsThatBreakTheSchemaGoToDlqAtOnceWithoutRetriesAndChangeNothing() throws Exception {
        StoredOrder order = seed(1, OrderStatus.AWAITING_PAYMENT, null);
        Map<String, Object> noOrder = confirmedData(order.id());
        noOrder.remove("orderId");
        deliver(PAYMENTS, "payment.confirmed", UUID.randomUUID(), order.id(), noOrder);
        Map<String, Object> upperCase = confirmedData(order.id());
        upperCase.put("orderId", order.id().toString().toUpperCase());
        deliver(PAYMENTS, "payment.confirmed", UUID.randomUUID(), order.id(), upperCase);
        deliver(PAYMENTS, "payment.refunded", UUID.randomUUID(), order.id(), refundedData(order.id(), "because"));
        deliver(DELIVERY, "delivery.accepted", UUID.randomUUID(), order.id(), acceptedData(order.id(), UUID.randomUUID(), "primary", "вчера"));
        deliver(IDENTITY, "user.anonymized", UUID.randomUUID(), BUYER, anonymizedData("не-идентификатор"));

        assertEquals(5, dlq.size());
        assertTrue(sleeps.isEmpty(), "неисправимая ошибка не повторяется");
        assertEquals(OrderStatus.AWAITING_PAYMENT, status(order.id()));
        assertEquals(0, store.processedCount());
        assertTrue(store.events().isEmpty());
        assertTrue(inventory.confirmed.isEmpty());
        assertNotNull(dlq.get(0).headers().lastHeader("dlq-error-message"));
        assertEquals("saga-orchestrator", new String(dlq.get(0).headers().lastHeader("dlq-consumer").value(), StandardCharsets.UTF_8));
        assertEquals("order-domain", new String(dlq.get(4).headers().lastHeader("dlq-consumer").value(), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------ TC-033: перестановка и повтор

    /**
     * Подтверждение платежа и истечение резерва приходят в любом порядке и по два раза. Итог один и тот же для обоих порядков, лишних строк
     * в Outbox нет, последнее событие не ломает результат первого (SM-01, ADR-004 «параллельные события одного заказа»).
     */
    @Tag("TC-033")
    @Test
    void confirmationAndExpiryInAnyOrderAndRedeliveredGiveOneOutcomeWithKeys() throws Exception {
        for (boolean expiryFirst : new boolean[] {false, true}) {
            setUp();
            StoredOrder order = seed(1, OrderStatus.AWAITING_PAYMENT, null);
            UUID confirmationId = UUID.randomUUID();
            UUID expiryId = UUID.randomUUID();
            ConsumerRecord<String, String> confirmation = record(PAYMENTS, "payment.confirmed", confirmationId, order.id(), confirmedData(order.id()));
            ConsumerRecord<String, String> expiry = record(INVENTORY, "reservation.expired", expiryId, order.id(), expiredData(order.id()));
            for (ConsumerRecord<String, String> first : expiryFirst ? List.of(expiry, confirmation) : List.of(confirmation, expiry)) {
                processor.process(first);
                processor.process(first);
            }

            String where = expiryFirst ? "резерв истёк раньше оплаты" : "оплата раньше истечения резерва";
            assertEquals(OrderStatus.PAID, status(order.id()), where);
            assertEquals(1, store.events("order.paid").size(), where);
            assertEquals(1, inventory.confirmed.size(), where);
            assertEquals(expiryFirst ? List.of("order.cancelled", "order.paid") : List.of("order.paid"), eventTypes(), where);
            assertEquals(expiryFirst, store.events("order.paid").get(0).data().get("latePayment"), where);
            assertEquals(2, store.processedCount(), where);
            assertTrue(dlq.isEmpty(), where);
        }
    }

    @Tag("TC-033")
    @Test
    void confirmationAndExpiryInAnyOrderAndRedeliveredGiveOneOutcomeWithoutKeys() throws Exception {
        for (boolean expiryFirst : new boolean[] {false, true}) {
            setUp();
            StoredOrder order = seed(1, OrderStatus.AWAITING_PAYMENT, null);
            inventory.result = new NoKeys(0);
            ConsumerRecord<String, String> confirmation = record(PAYMENTS, "payment.confirmed", UUID.randomUUID(), order.id(), confirmedData(order.id()));
            ConsumerRecord<String, String> expiry = record(INVENTORY, "reservation.expired", UUID.randomUUID(), order.id(), expiredData(order.id()));
            for (ConsumerRecord<String, String> first : expiryFirst ? List.of(expiry, confirmation) : List.of(confirmation, expiry)) {
                processor.process(first);
                processor.process(first);
            }

            String where = expiryFirst ? "резерв истёк раньше оплаты" : "оплата раньше истечения резерва";
            assertEquals(OrderStatus.CANCELLED, status(order.id()), where);
            assertEquals(CancelReason.RESERVATION_EXPIRED, store.get(order.id()).cancelReason(), where);
            assertEquals(List.of("order.cancelled", "order.refund-requested"), eventTypes(), where);
            assertEquals(2, store.processedCount(), where);
            assertTrue(dlq.isEmpty(), where);
        }
    }
}
