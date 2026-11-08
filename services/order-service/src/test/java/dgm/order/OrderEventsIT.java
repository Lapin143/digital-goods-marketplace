package dgm.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** Заказ товара по API продавца {@code seller}: способ выдачи берётся из карточки товара. */
    private static Placed placeApi(UUID seller) throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = NEIGHBOURS.apiProduct(seller, "Доступ по API " + UUID.randomUUID().toString().substring(0, 8), PRICE, 20);
        String email = "buyer-" + buyer.toString().substring(0, 8) + "@mail.example";
        Response response = create(buyerToken(buyer, email), product, QUANTITY);
        assertEquals(201, response.status(), response.body());
        return new Placed(buyer, product, (String) response.json().get("orderId"), email);
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

    /** {@code delivery.seller-timeout} от {@code delivery-service}: система продавца не ответила за 10 минут (Р-190). */
    private static UUID sellerTimeout(String orderId, UUID sellerId, UUID eventId) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", UUID.randomUUID().toString());
        data.put("orderId", orderId);
        data.put("sellerId", sellerId.toString());
        data.put("waitedSeconds", 600);
        return publish(delivery, "delivery-service", "delivery.events", "Delivery", "delivery.seller-timeout", eventId, orderId, data);
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
        assertEquals("key_pool", data.get("issuanceMethod"));
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

    // ------------------------------------------------------------------------------------------------ TC-531, TC-532, TC-962: заказ по API и молчание продавца

    @Tag("TC-531")
    @Test
    void anApiProductIsOrderedLikeAnyOtherAndTheOrderRemembersTheMethodAndPublishesItInOrderPaid() throws Exception {
        UUID seller = UUID.randomUUID();
        Placed p = placeApi(seller);

        Map<String, Object> created = order(p.orderId());
        assertEquals("seller_api", created.get("issuance_method"));
        assertEquals("awaiting_payment", created.get("status"));
        assertEquals(1, NEIGHBOURS.calls("inventory").stream().filter(c -> c.path().endsWith("/reservations")).count(), "резерв по количеству, как у любого товара");

        confirmed(p.orderId());
        awaitStatus(p.orderId(), "paid");

        assertEquals("seller_api", data(events(p.orderId(), "order.paid").get(0)).get("issuanceMethod"));
        assertEquals("paid", status(p.orderId()), "до ответа продавца и принятого письма заказ остаётся «оплачен»");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> db.update("update orders.orders set issuance_method = 'key_pool' where id = ?", UUID.fromString(p.orderId())), "способ выдачи в заказе не меняется");
        assertEquals("seller_api", order(p.orderId()).get("issuance_method"));
    }

    @Tag("TC-532")
    @Test
    void sellerSilenceCancelsThePaidApiOrderAsksForTheRefundOnceAndALateOrRepeatedTimeoutChangesNothing() throws Exception {
        UUID seller = UUID.randomUUID();
        Placed p = placeApi(seller);
        confirmed(p.orderId());
        awaitStatus(p.orderId(), "paid");
        UUID eventId = UUID.randomUUID();

        sellerTimeout(p.orderId(), seller, eventId);

        awaitStatus(p.orderId(), "cancelled");
        awaitProcessed(SAGA, eventId);
        assertEquals("seller_api_timeout", order(p.orderId()).get("cancel_reason"));
        assertNotNull(order(p.orderId()).get("paid_at"), "время оплаты сохраняется");
        List<Map<String, Object>> cancelled = events(p.orderId(), "order.cancelled");
        assertEquals(1, cancelled.size());
        assertEquals("seller_api_timeout", data(cancelled.get(0)).get("cancelReason"));
        assertEquals(p.product().toString(), data(cancelled.get(0)).get("productId"));
        List<Map<String, Object>> refunds = events(p.orderId(), "order.refund-requested");
        assertEquals(1, refunds.size(), "команда на возврат уходит в Outbox той же транзакцией, что и отмена");
        assertEquals("seller_api_timeout", data(refunds.get(0)).get("refundReason"));
        assertEquals(TOTAL, amount(data(refunds.get(0)).get("amount")), "возврат всей суммы заказа");

        long rows = eventCount(p.orderId());
        sellerTimeout(p.orderId(), seller, eventId);
        UUID other = sellerTimeout(p.orderId(), seller, UUID.randomUUID());
        awaitProcessed(SAGA, other);

        assertEquals("cancelled", status(p.orderId()));
        assertEquals(1, events(p.orderId(), "order.refund-requested").size(), "повтор события и второе событие второго возврата не рождают (INV-06, INV-15)");
        assertEquals(rows, eventCount(p.orderId()));
    }

    @Tag("TC-532")
    @Test
    void aTimeoutThatArrivesAfterTheIssueAndATimeoutOfAKeyPoolOrderLeaveTheOrdersAlone() throws Exception {
        UUID seller = UUID.randomUUID();
        Placed api = placeApi(seller);
        confirmed(api.orderId());
        awaitStatus(api.orderId(), "paid");
        accepted(api.orderId(), UUID.randomUUID(), "2026-10-19T10:00:00.000Z");
        awaitStatus(api.orderId(), "issued");
        Placed pool = place();
        confirmed(pool.orderId());
        awaitStatus(pool.orderId(), "paid");
        long apiRows = eventCount(api.orderId());
        long poolRows = eventCount(pool.orderId());

        UUID late = sellerTimeout(api.orderId(), seller, UUID.randomUUID());
        UUID wrong = sellerTimeout(pool.orderId(), SELLER, UUID.randomUUID());
        awaitProcessed(SAGA, late);
        awaitProcessed(SAGA, wrong);

        assertEquals("issued", status(api.orderId()), "из «выдан» отмены нет");
        assertEquals("paid", status(pool.orderId()), "заказ по ключам не ждёт продавца");
        assertEquals(apiRows, eventCount(api.orderId()));
        assertEquals(poolRows, eventCount(pool.orderId()));
    }

    @Tag("TC-962")
    @Test
    void theChainOfTheTimeoutIsVisibleToTheSellerFromTheCancellationToTheRefund() throws Exception {
        UUID seller = UUID.randomUUID();
        String token = sellerToken(seller);
        Placed p = placeApi(seller);
        confirmed(p.orderId());
        awaitStatus(p.orderId(), "paid");
        assertEquals("paid", sellerOrder(token, p.orderId()).get("status"));
        assertEquals("seller_api", sellerOrder(token, p.orderId()).get("issuanceMethod"));

        sellerTimeout(p.orderId(), seller, UUID.randomUUID());
        awaitStatus(p.orderId(), "cancelled");

        Map<String, Object> cancelled = sellerOrder(token, p.orderId());
        assertEquals("cancelled", cancelled.get("status"));
        assertEquals("seller_api_timeout", cancelled.get("cancelReason"));
        assertNotNull(cancelled.get("paidAt"));
        assertNull(cancelled.get("refundedAt"), "деньги ещё не вернулись");

        UUID refund = refunded(p.orderId(), "seller_api_timeout");
        awaitStatus(p.orderId(), "refunded");
        awaitProcessed(SAGA, refund);

        Map<String, Object> refunded = sellerOrder(token, p.orderId());
        assertEquals("refunded", refunded.get("status"));
        assertEquals("seller_api_timeout", refunded.get("cancelReason"), "причина отмены остаётся в возвращённом заказе");
        assertNotNull(refunded.get("refundedAt"));
        assertNotNull(order(p.orderId()).get("refunded_at"));
        List<Map<String, Object>> refundedEvents = events(p.orderId(), "order.refunded");
        assertEquals(1, refundedEvents.size());
        assertEquals("seller_api_timeout", data(refundedEvents.get(0)).get("refundReason"));
        // Деньги и остаток возвращают платёж и остатки по этим двум событиям, здесь проверено, что они изданы по одному разу
        assertEquals(1, events(p.orderId(), "order.cancelled").size());
        assertEquals(1, events(p.orderId(), "order.refund-requested").size());
    }

    /** Заказ продавца из {@code GET /api/v1/seller/orders} по идентификатору. */
    private static Map<String, Object> sellerOrder(String token, String orderId) throws IOException {
        Response response = read(token, "/api/v1/seller/orders?limit=100");
        assertEquals(200, response.status(), response.body());
        for (Object item : (List<?>) response.json().get("items")) {
            Map<?, ?> row = (Map<?, ?>) item;
            if (orderId.equals(row.get("orderId"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) row;
                return typed;
            }
        }
        throw new AssertionError("заказа " + orderId + " нет в списке продавца");
    }
}
