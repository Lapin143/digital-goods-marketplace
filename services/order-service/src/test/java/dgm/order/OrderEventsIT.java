package dgm.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.idempotency.IdempotencyKeys;
import dgm.kit.json.Json;
import dgm.kit.kafka.KafkaClients;
import dgm.kit.outbox.EnvelopeBuilder;
import dgm.kit.outbox.OutboxRow;
import dgm.kit.outbox.OutgoingEvent;
import dgm.kit.testing.Stand;
import dgm.kit.trace.TraceContext;
import dgm.order.Neighbours.Call;
import dgm.order.Neighbours.ConfirmMode;
import dgm.order.Neighbours.Mode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * События саги на стенде (ADR-004, ADR-013): настоящий потребитель Kafka сервиса читает {@code payment.events}, {@code inventory.events},
 * {@code delivery.events} и {@code identity.events}, события публикуют соседи от имени своих сертификатов (права Kafka выданы по CN), результат
 * проверяется по строке заказа, Outbox и таблице {@code processed_event}. Подтверждение резерва после оплаты отвечает заглушка {@link Neighbours}.
 * Обработка идёт фоном, поэтому итог ждут опросом базы.
 */
class OrderEventsIT extends OrderStandIT {

    private static final long PRICE = 149_900;
    private static final int QUANTITY = 2;
    private static final long TOTAL = PRICE * QUANTITY;
    private static final Duration LIMIT = Duration.ofSeconds(40);
    private static final UUID SELLER = UUID.randomUUID();
    private static final String SAGA = "saga-orchestrator";
    private static final String DOMAIN = "order-domain";
    private static final String ANONYMIZED = "anonymized@anonymized.invalid";

    private static KafkaProducer<String, String> payments;
    private static KafkaProducer<String, String> inventory;
    private static KafkaProducer<String, String> delivery;
    private static KafkaProducer<String, String> platform;

    /** Заказ, оформленный через API. */
    private record Placed(UUID buyer, UUID product, String orderId, String email) {
    }

    @BeforeAll
    static void connectKafka() {
        payments = producer("payment-service");
        inventory = producer("inventory-service");
        delivery = producer("delivery-service");
        platform = producer("platform-service");
    }

    @AfterAll
    static void closeKafka() {
        for (KafkaProducer<String, String> producer : List.of(payments, inventory, delivery, platform)) {
            producer.close(Duration.ofSeconds(5));
        }
    }

    private static KafkaProducer<String, String> producer(String service) {
        return KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity(service), service + "-order-events-it");
    }

    // ------------------------------------------------------------------------------------------------ данные

    private static Placed place(UUID buyer) throws IOException {
        UUID product = NEIGHBOURS.product(SELLER, "Игра " + UUID.randomUUID().toString().substring(0, 8), PRICE, 20);
        String email = "buyer-" + buyer.toString().substring(0, 8) + "@mail.example";
        Response response = create(buyerToken(buyer, email), product, QUANTITY);
        assertEquals(201, response.status(), response.body());
        return new Placed(buyer, product, (String) response.json().get("orderId"), email);
    }

    private static Placed place() throws IOException {
        return place(UUID.randomUUID());
    }

    private static Map<String, Object> money(long amount) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", "RUB");
        return money;
    }

    private static UUID publish(KafkaProducer<String, String> producer, String source, String topic, String aggregateType, String type, UUID eventId,
                                String aggregateId, Map<String, Object> data) throws Exception {
        String headers = "{\"schemaversion\": 1, \"traceparent\": \"" + TraceContext.fresh().traceparent() + "\", \"correlationid\": \"" + UUID.randomUUID()
                + "\"}";
        OutgoingEvent event = new EnvelopeBuilder(source).build(new OutboxRow(1, eventId, aggregateType, aggregateId, type, topic, Json.write(data), headers,
                Instant.now(), 0));
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.value());
        record.headers().add("ce_type", event.type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent", event.traceparent().getBytes(StandardCharsets.UTF_8));
        producer.send(record).get(20, TimeUnit.SECONDS);
        return eventId;
    }

    /** {@code payment.confirmed} от {@code payment-service}. */
    private static UUID confirmed(String orderId, UUID eventId) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", UUID.randomUUID().toString());
        data.put("orderId", orderId);
        data.put("amount", money(TOTAL));
        data.put("confirmedAt", "2026-10-19T10:00:00.000Z");
        return publish(payments, "payment-service", "payment.events", "Payment", "payment.confirmed", eventId, orderId, data);
    }

    private static UUID confirmed(String orderId) throws Exception {
        return confirmed(orderId, UUID.randomUUID());
    }

    private static UUID rejected(String orderId) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", UUID.randomUUID().toString());
        data.put("orderId", orderId);
        data.put("reason", "declined");
        return publish(payments, "payment-service", "payment.events", "Payment", "payment.rejected", UUID.randomUUID(), orderId, data);
    }

    private static UUID refunded(String orderId, String reason) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", UUID.randomUUID().toString());
        data.put("orderId", orderId);
        data.put("amount", money(TOTAL));
        data.put("refundReason", reason);
        data.put("manual", false);
        data.put("refundedAt", "2026-10-19T10:01:00.000Z");
        return publish(payments, "payment-service", "payment.events", "Payment", "payment.refunded", UUID.randomUUID(), orderId, data);
    }

    private static UUID expired(Placed p) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reservationId", UUID.randomUUID().toString());
        data.put("orderId", p.orderId());
        data.put("productId", p.product().toString());
        data.put("quantity", QUANTITY);
        data.put("expiredBy", "timer");
        return publish(inventory, "inventory-service", "inventory.events", "Reservation", "reservation.expired", UUID.randomUUID(), p.orderId(), data);
    }

    private static UUID accepted(String orderId, UUID deliveryId, String acceptedAt) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", deliveryId.toString());
        data.put("orderId", orderId);
        data.put("deliveryType", "primary");
        data.put("attemptNo", 1);
        data.put("acceptedAt", acceptedAt);
        return publish(delivery, "delivery-service", "delivery.events", "Delivery", "delivery.accepted", UUID.randomUUID(), deliveryId.toString(), data);
    }

    private static UUID anonymized(UUID user) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.toString());
        data.put("requestedBy", "user");
        return publish(platform, "platform-service", "identity.events", "User", "user.anonymized", UUID.randomUUID(), user.toString(), data);
    }

    // ------------------------------------------------------------------------------------------------ проверки

    private static boolean processed(String consumer, UUID eventId) {
        Integer n = db.queryForObject("select count(*) from public.processed_event where consumer = ? and event_id = ?", Integer.class, consumer, eventId);
        return n != null && n > 0;
    }

    private static void awaitStatus(String orderId, String expected) {
        assertTrue(await(LIMIT, () -> expected.equals(status(orderId))), "заказ " + orderId + " не стал «" + expected + "», сейчас «" + status(orderId) + "»");
    }

    private static void awaitProcessed(String consumer, UUID eventId) {
        assertTrue(await(LIMIT, () -> processed(consumer, eventId)), "событие " + eventId + " не обработано потребителем " + consumer);
    }

    private static List<Call> confirmCalls() {
        return NEIGHBOURS.calls("inventory").stream().filter(c -> c.path().endsWith("/reservations/confirm")).toList();
    }

    private static long amount(Object money) {
        return ((Number) ((Map<?, ?>) money).get("amount")).longValue();
    }

    private static String issuedAt(String orderId) {
        return db.queryForObject("select to_char(issued_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') from orders.orders where id = ?", String.class,
                UUID.fromString(orderId));
    }

    private static String address(String orderId) {
        return (String) order(orderId).get("delivery_address");
    }

    // ------------------------------------------------------------------------------------------------ TC-516: оплата

    @Tag("TC-516")
    @Test
    void confirmedPaymentPaysTheOrderAndPublishesOrderPaidAndARepeatChangesNothing() throws Exception {
        Placed p = place();
        UUID eventId = confirmed(p.orderId());

        awaitStatus(p.orderId(), "paid");

        Map<String, Object> row = order(p.orderId());
        assertNotNull(row.get("paid_at"));
        assertNull(row.get("cancel_reason"));
        List<Map<String, Object>> paid = events(p.orderId(), "order.paid");
        assertEquals(1, paid.size(), "order.paid в Outbox той же транзакцией, что и статус");
        assertEquals("order.events", paid.get(0).get("topic"));
        assertEquals("Order", paid.get(0).get("aggregate_type"));
        Map<String, Object> data = data(paid.get(0));
        assertEquals(p.orderId(), data.get("orderId"));
        assertEquals(p.buyer().toString(), data.get("buyerId"));
        assertEquals(SELLER.toString(), data.get("sellerId"));
        assertEquals(p.product().toString(), data.get("productId"));
        assertEquals(QUANTITY, ((Number) data.get("quantity")).intValue());
        assertEquals(TOTAL, amount(data.get("total")));
        assertEquals(5_996, amount(data.get("commission")));
        assertEquals(false, data.get("latePayment"));
        assertEquals(Map.of("channel", "email", "address", p.email()), data.get("delivery"));
        assertTrue(((String) data.get("paidAt")).endsWith("Z"));

        List<Call> confirms = confirmCalls();
        assertEquals(1, confirms.size(), "резерв подтверждён один раз");
        assertEquals(IdempotencyKeys.derived("confirm", UUID.fromString(p.orderId())).toString(), confirms.get(0).headers().get("idempotency-key"));
        awaitProcessed(SAGA, eventId);

        long rows = eventCount(p.orderId());
        confirmed(p.orderId(), eventId);
        UUID other = confirmed(p.orderId());
        awaitProcessed(SAGA, other);

        assertEquals("paid", status(p.orderId()));
        assertEquals(1, events(p.orderId(), "order.paid").size(), "повтор и второе подтверждение второго order.paid не рождают");
        assertEquals(rows, eventCount(p.orderId()), "строк в Outbox не прибавилось");
        assertEquals(1, confirmCalls().size(), "повтор того же события и подтверждение оплаченного заказа соседа не вызывают");
    }

    @Tag("TC-516")
    @Test
    void aReservationCreatedAgainForAnAwaitingOrderIsStillAnOrdinaryPayment() throws Exception {
        Placed p = place();
        NEIGHBOURS.confirmIs(ConfirmMode.RE_RESERVED);

        confirmed(p.orderId());

        awaitStatus(p.orderId(), "paid");
        assertEquals(false, data(events(p.orderId(), "order.paid").get(0)).get("latePayment"));
    }

    @Tag("TC-516")
    @Test
    void confirmationForAnUnknownOrderIsIgnoredWithoutCallingTheNeighbour() throws Exception {
        UUID unknown = UUID.randomUUID();
        UUID eventId = confirmed(unknown.toString());

        awaitProcessed(SAGA, eventId);

        assertEquals(0, confirmCalls().size());
        assertNull(order(unknown.toString()));
        assertEquals(0, eventCount(unknown.toString()));
    }

    // ------------------------------------------------------------------------------------------------ TC-517: поздняя оплата

    @Tag("TC-517")
    @Test
    void lateConfirmationWithFreeKeysPaysTheCancelledOrderAndMarksItLate() throws Exception {
        Placed p = place();
        expired(p);
        awaitStatus(p.orderId(), "cancelled");
        assertEquals("reservation_expired", order(p.orderId()).get("cancel_reason"));
        NEIGHBOURS.confirmIs(ConfirmMode.RE_RESERVED);

        confirmed(p.orderId());

        awaitStatus(p.orderId(), "paid");
        Map<String, Object> row = order(p.orderId());
        assertNull(row.get("cancel_reason"), "причина отмены снята: заказ оплачен");
        assertNotNull(row.get("paid_at"));
        List<Map<String, Object>> paid = events(p.orderId(), "order.paid");
        assertEquals(1, paid.size());
        assertEquals(true, data(paid.get(0)).get("latePayment"));
        assertEquals(1, events(p.orderId(), "order.cancelled").size(), "отмена осталась в истории событий");
        assertEquals(0, events(p.orderId(), "order.refund-requested").size());
        assertEquals(1, confirmCalls().size());
    }

    @Tag("TC-517")
    @Test
    void lateConfirmationWithoutKeysKeepsTheOrderCancelledAsksForTheWholeRefundAndTheRefundClosesTheOrder() throws Exception {
        Placed p = place();
        expired(p);
        awaitStatus(p.orderId(), "cancelled");
        NEIGHBOURS.confirmIs(ConfirmMode.NO_KEYS, 1);

        UUID eventId = confirmed(p.orderId());

        awaitProcessed(SAGA, eventId);
        assertEquals("cancelled", status(p.orderId()));
        assertEquals(0, events(p.orderId(), "order.paid").size());
        List<Map<String, Object>> requests = events(p.orderId(), "order.refund-requested");
        assertEquals(1, requests.size(), "команда возврата одна");
        assertEquals("order.events", requests.get(0).get("topic"));
        Map<String, Object> command = data(requests.get(0));
        assertEquals(p.orderId(), command.get("orderId"));
        assertEquals(TOTAL, amount(command.get("amount")), "возврат всегда на всю сумму заказа");
        assertEquals("late_payment", command.get("refundReason"));

        refunded(p.orderId(), "late_payment");

        awaitStatus(p.orderId(), "refunded");
        List<Map<String, Object>> done = events(p.orderId(), "order.refunded");
        assertEquals(1, done.size());
        Map<String, Object> data = data(done.get(0));
        assertEquals(p.buyer().toString(), data.get("buyerId"));
        assertEquals(TOTAL, amount(data.get("total")));
        assertEquals("late_payment", data.get("refundReason"));
    }

    @Tag("TC-517")
    @Test
    void confirmationWithoutKeysForAnAwaitingOrderCancelsItAndAsksForTheRefund() throws Exception {
        Placed p = place();
        NEIGHBOURS.confirmIs(ConfirmMode.NO_KEYS, 0);

        confirmed(p.orderId());

        awaitStatus(p.orderId(), "cancelled");
        assertEquals("reservation_expired", order(p.orderId()).get("cancel_reason"));
        assertTrue(await(LIMIT, () -> events(p.orderId(), "order.refund-requested").size() == 1), "команда возврата не появилась");
        assertEquals(1, events(p.orderId(), "order.cancelled").size(), "резерв снимается событием отмены");
        assertEquals(0, events(p.orderId(), "order.paid").size());
    }

    @Tag("TC-517")
    @Test
    void noReservationAtAllIsTreatedLikeNoKeys() throws Exception {
        Placed p = place();
        expired(p);
        awaitStatus(p.orderId(), "cancelled");
        NEIGHBOURS.confirmIs(ConfirmMode.NOT_FOUND);

        UUID eventId = confirmed(p.orderId());

        awaitProcessed(SAGA, eventId);
        assertEquals("cancelled", status(p.orderId()));
        assertEquals(1, events(p.orderId(), "order.refund-requested").size());
    }

    @Tag("TC-517")
    @Test
    void anInventoryOutageIsRetriedAndThePaymentIsAppliedWhenTheServiceReturns() throws Exception {
        Placed p = place();
        NEIGHBOURS.inventoryIs(Mode.DOWN);

        UUID eventId = confirmed(p.orderId());

        assertTrue(await(LIMIT, () -> !confirmCalls().isEmpty()), "потребитель не обратился к сервису остатков");
        assertEquals("awaiting_payment", status(p.orderId()), "пока сосед недоступен, заказ не меняется");
        NEIGHBOURS.inventoryIs(Mode.OK);

        awaitStatus(p.orderId(), "paid");
        awaitProcessed(SAGA, eventId);
        assertTrue(confirmCalls().size() >= 2, "были повторы с паузой");
        assertEquals(1, events(p.orderId(), "order.paid").size());
    }

    // ------------------------------------------------------------------------------------------------ TC-518: остальные события

    @Tag("TC-518")
    @Test
    void rejectedPaymentCancelsTheAwaitingOrderAndALaterConfirmationIsAnAnomalyThatChangesNothing() throws Exception {
        Placed p = place();

        rejected(p.orderId());

        awaitStatus(p.orderId(), "cancelled");
        assertEquals("payment_declined", order(p.orderId()).get("cancel_reason"));
        List<Map<String, Object>> cancelled = events(p.orderId(), "order.cancelled");
        assertEquals(1, cancelled.size());
        assertEquals("payment_declined", data(cancelled.get(0)).get("cancelReason"));
        assertEquals(p.product().toString(), data(cancelled.get(0)).get("productId"));

        UUID eventId = confirmed(p.orderId());
        awaitProcessed(SAGA, eventId);

        assertEquals("cancelled", status(p.orderId()));
        assertEquals(0, events(p.orderId(), "order.paid").size());
        assertEquals(0, events(p.orderId(), "order.refund-requested").size(), "аномалию разбирает администратор, автовозврата нет");
        assertEquals(0, confirmCalls().size());
    }

    @Tag("TC-518")
    @Test
    void expiredReservationCancelsTheAwaitingOrderOnceAndARepeatIsIgnored() throws Exception {
        Placed p = place();

        expired(p);

        awaitStatus(p.orderId(), "cancelled");
        assertEquals("reservation_expired", order(p.orderId()).get("cancel_reason"));
        UUID again = expired(p);
        awaitProcessed(SAGA, again);
        assertEquals(1, events(p.orderId(), "order.cancelled").size());
    }

    @Tag("TC-518")
    @Test
    void acceptedDeliveryIssuesThePaidOrderOnceAndTheMomentOfTheFirstIssueIsKept() throws Exception {
        Placed p = place();
        confirmed(p.orderId());
        awaitStatus(p.orderId(), "paid");
        UUID deliveryId = UUID.randomUUID();

        UUID first = accepted(p.orderId(), deliveryId, "2026-10-19T10:00:00.456Z");

        awaitStatus(p.orderId(), "issued");
        awaitProcessed(SAGA, first);
        assertEquals("2026-10-19T10:00:00.456Z", issuedAt(p.orderId()));
        List<Map<String, Object>> issued = events(p.orderId(), "order.issued");
        assertEquals(1, issued.size());
        Map<String, Object> data = data(issued.get(0));
        assertEquals(deliveryId.toString(), data.get("deliveryId"));
        assertEquals("2026-10-19T10:00:00.456Z", data.get("issuedAt"));
        assertEquals(TOTAL, amount(data.get("total")));

        UUID repeat = accepted(p.orderId(), UUID.randomUUID(), "2026-10-19T12:00:00.000Z");
        awaitProcessed(SAGA, repeat);

        assertEquals("issued", status(p.orderId()));
        assertEquals("2026-10-19T10:00:00.456Z", issuedAt(p.orderId()), "повторная отправка момент первой выдачи не сдвигает (INV-17)");
        assertEquals(1, events(p.orderId(), "order.issued").size());
    }

    @Tag("TC-518")
    @Test
    void acceptedDeliveryOfAnAwaitingOrderIsIgnored() throws Exception {
        Placed p = place();

        UUID eventId = accepted(p.orderId(), UUID.randomUUID(), "2026-10-19T10:00:00.000Z");

        awaitProcessed(SAGA, eventId);
        assertEquals("awaiting_payment", status(p.orderId()));
        assertEquals(0, events(p.orderId(), "order.issued").size());
    }

    @Tag("TC-518")
    @Test
    void anonymizedUserLosesTheAddressInAllOrdersAndOtherBuyersKeepTheirs() throws Exception {
        UUID buyer = UUID.randomUUID();
        Placed first = place(buyer);
        Placed second = place(buyer);
        Placed other = place();
        confirmed(second.orderId());
        awaitStatus(second.orderId(), "paid");
        long rows = eventCount(first.orderId()) + eventCount(second.orderId());

        UUID eventId = anonymized(buyer);

        awaitProcessed(DOMAIN, eventId);
        assertEquals(ANONYMIZED, address(first.orderId()));
        assertEquals(ANONYMIZED, address(second.orderId()));
        assertEquals(other.email(), address(other.orderId()));
        assertEquals("awaiting_payment", status(first.orderId()), "статусы и суммы остаются");
        assertEquals("paid", status(second.orderId()));
        assertEquals(rows, eventCount(first.orderId()) + eventCount(second.orderId()), "обезличивание событий не издаёт");

        UUID repeat = anonymized(buyer);
        awaitProcessed(DOMAIN, repeat);
        assertEquals(ANONYMIZED, address(first.orderId()));
        assertFalse(processed(SAGA, eventId), "событие user.anonymized обрабатывает только order-domain");
    }
}
