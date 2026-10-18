package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.reservation.timer.RedisExpiryQueue;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import dgm.kit.kafka.KafkaClients;
import dgm.kit.outbox.EnvelopeBuilder;
import dgm.kit.outbox.OutboxRow;
import dgm.kit.outbox.OutgoingEvent;
import dgm.kit.redis.KitRedis;
import dgm.kit.testing.Stand;
import dgm.kit.trace.TraceContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Резерв ключей на стенде (шаг 5 среза 2; SM-08, SM-03, INV-01, INV-05, INV-06, ADR-007, ADR-012, US-5.2): резерв 3 из 5, отказ при
 * нехватке, повтор запроса, истечение срока таймером, потерянный таймер и сверка, снятие по {@code order.cancelled}, версии
 * {@code stock.changed} без пропусков, права вызывающих (ST-02). Сервис работает в процессе теста; срок резерва 6 секунд задан
 * свойствами {@link InventoryStandIT}, на стенде и сервере он берётся из параметра платформы {@code reservation.ttl-seconds}.
 */
class ReservationIT extends KeyStandIT {

    private static final String RESERVATIONS = "/internal/v1/reservations";
    private static final Duration LIMIT = Duration.ofSeconds(60);

    private static StandHttp orders;
    private static KafkaProducer<String, String> orderEvents;

    @Autowired
    private KitRedis redis;

    @BeforeAll
    static void clients() {
        orders = StandHttp.asService("order-service", PORT);
        orderEvents = KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("order-service"), "order-service-it");
    }

    @AfterAll
    static void close() {
        orderEvents.close(Duration.ofSeconds(5));
    }

    /** Товар продавца с пулом из {@code count} ключей, загруженных через шлюз. */
    private record Pool(Seller seller, UUID productId) {
    }

    private static Pool pool(int count) throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product("published", "key_pool");
        String[] keys = new String[count];
        for (int i = 0; i < count; i++) {
            keys[i] = key();
        }
        Response loaded = batch(seller, productId, keys);
        assertEquals(200, loaded.status(), loaded.body());
        return new Pool(seller, productId);
    }

    private static Response reserve(StandHttp caller, UUID orderId, UUID productId, int quantity, String idempotencyKey) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId.toString());
        body.put("productId", productId.toString());
        body.put("quantity", quantity);
        return caller.post(RESERVATIONS, Json.write(body), "Idempotency-Key", idempotencyKey);
    }

    /** Вызов саги: ключ идемпотентности равен идентификатору заказа. */
    private static Response reserve(UUID orderId, UUID productId, int quantity) throws IOException {
        return reserve(orders, orderId, productId, quantity, orderId.toString());
    }

    private static Map<String, Object> reservation(UUID orderId) {
        List<Map<String, Object>> rows = db.queryForList("select id, status, quantity, release_reason, expires_at, created_at from inventory.reservation "
                + "where order_id = ? order by created_at desc, id desc", orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String status(UUID orderId) {
        Map<String, Object> row = reservation(orderId);
        return row == null ? null : (String) row.get("status");
    }

    private static int keys(UUID productId, String status) {
        Integer n = db.queryForObject("select count(*) from inventory.key where product_id = ? and status = ?", Integer.class, productId, status);
        return n == null ? 0 : n;
    }

    private static List<Map<String, Object>> events(UUID aggregateId, String type) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String payload : db.queryForList("select payload::text from public.outbox where aggregate_id = ? and event_type = ? order by id", String.class,
                aggregateId.toString(), type)) {
            events.add(Json.parseObject(payload));
        }
        return events;
    }

    private static long stockVersion(UUID productId) {
        Long version = db.queryForObject("select stock_version from inventory.product_copy where product_id = ?", Long.class, productId);
        return version == null ? 0 : version;
    }

    /** Публикует {@code order.cancelled} от имени order-service (права Kafka выданы по CN сертификата). Ключ записи равен идентификатору заказа. */
    private static UUID publishCancelled(UUID orderId, String cancelReason) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId.toString());
        data.put("buyerId", UUID.randomUUID().toString());
        data.put("cancelReason", cancelReason);
        UUID eventId = UUID.randomUUID();
        send(eventId, orderId, data);
        return eventId;
    }

    private static void send(UUID eventId, UUID orderId, Map<String, Object> data) throws Exception {
        String headers = "{\"schemaversion\": 1, \"traceparent\": \"" + TraceContext.fresh().traceparent() + "\", \"correlationid\": \"" + UUID.randomUUID()
                + "\"}";
        OutgoingEvent event = new EnvelopeBuilder("order-service").build(new OutboxRow(1, eventId, "Order", orderId.toString(), "order.cancelled",
                "order.events", Json.write(data), headers, Instant.now(), 0));
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.value());
        record.headers().add("ce_type", event.type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent", event.traceparent().getBytes(StandardCharsets.UTF_8));
        orderEvents.send(record).get(20, TimeUnit.SECONDS);
    }

    private static boolean processed(UUID eventId) {
        Integer n = db.queryForObject("select count(*) from public.processed_event where consumer = 'reservation-service' and event_id = ?", Integer.class,
                eventId);
        return n != null && n > 0;
    }

    // ---------------------------------------------------------------- резерв

    @Test
    void threeOutOfFiveAreReservedWithTheConfiguredTtlAndTheStockIsPublished() throws Exception {
        Pool pool = pool(5);
        UUID orderId = UUID.randomUUID();
        Instant before = Instant.now();
        Response response = reserve(orderId, pool.productId(), 3);
        assertEquals(201, response.status(), response.body());
        assertEquals("no-store", response.header("Cache-Control"));
        Map<String, Object> body = response.json();
        assertEquals("active", body.get("status"));
        assertEquals(3, body.get("quantity"));
        assertEquals(orderId.toString(), body.get("orderId"));
        assertEquals(pool.productId().toString(), body.get("productId"));
        assertEquals("/internal/v1/reservations/" + body.get("reservationId"), response.header("Location"));
        Instant expiresAt = Instant.parse((String) body.get("expiresAt"));
        assertTrue(expiresAt.isAfter(before.plusSeconds(4)) && expiresAt.isBefore(Instant.now().plusSeconds(8)), "срок это настройка 6 секунд от часов: " + expiresAt);

        assertEquals(3, keys(pool.productId(), "reserved"));
        assertEquals(2, keys(pool.productId(), "free"));
        assertEquals(3, db.queryForObject("select count(*) from inventory.key where reservation_id = ?::uuid and order_id = ?", Integer.class,
                body.get("reservationId"), orderId));
        Map<String, Object> row = reservation(orderId);
        assertEquals("active", row.get("status"));
        assertEquals(3, row.get("quantity"));

        List<Map<String, Object>> stock = stockEvents(pool.productId());
        assertEquals(2, stock.size(), "загрузка и резерв: по событию остатка на каждое изменение пула");
        assertEquals(5, stock.get(0).get("available"));
        assertEquals(2, stock.get(1).get("available"));
        assertEquals(((Number) stock.get(0).get("version")).longValue() + 1, ((Number) stock.get(1).get("version")).longValue());

        Response seen = stock(pool.seller(), pool.productId());
        assertEquals(2, seen.json().get("available"));
        assertEquals(3, seen.json().get("reserved"));
    }

    @Test
    void reservingMoreThanIsFreeAnswers409WithTheAvailableCountAndChangesNothing() throws Exception {
        Pool pool = pool(5);
        long version = stockVersion(pool.productId());
        int events = stockEvents(pool.productId()).size();
        UUID orderId = UUID.randomUUID();
        Response response = reserve(orderId, pool.productId(), 6);
        assertEquals(409, response.status(), response.body());
        assertEquals("insufficient-stock", code(response));
        assertEquals(5, response.json().get("available"));
        assertNull(reservation(orderId));
        assertEquals(5, keys(pool.productId(), "free"));
        assertEquals(0, keys(pool.productId(), "reserved"));
        assertEquals(version, stockVersion(pool.productId()), "отказ не меняет версию остатка");
        assertEquals(events, stockEvents(pool.productId()).size(), "и не пишет событие");
    }

    @Test
    void theSameKeyReplaysTheReservationAndANewKeyForAnActiveReservationIs409() throws Exception {
        Pool pool = pool(5);
        UUID orderId = UUID.randomUUID();
        Response first = reserve(orderId, pool.productId(), 2);
        assertEquals(201, first.status(), first.body());
        Response replay = reserve(orderId, pool.productId(), 2);
        assertEquals(201, replay.status(), replay.body());
        assertEquals("true", replay.header("Idempotency-Replayed"));
        assertEquals(first.json().get("reservationId"), replay.json().get("reservationId"), "повтор с тем же ключом возвращает прежний резерв");

        Response another = reserve(orders, orderId, pool.productId(), 2, key());
        assertEquals(409, another.status(), another.body());
        assertEquals("state-conflict", code(another));
        assertEquals(1, db.queryForObject("select count(*) from inventory.reservation where order_id = ?", Integer.class, orderId), "у заказа один резерв (INV-06)");
        assertEquals(2, keys(pool.productId(), "reserved"));
    }

    @Test
    void anUnknownOrUnpublishedProductAndABadBodyAreRefusedWithoutSideEffects() throws Exception {
        UUID orderId = UUID.randomUUID();
        assertEquals(404, reserve(orderId, UUID.randomUUID(), 1).status());
        Seller seller = new Seller();
        UUID blocked = seller.product("blocked", "key_pool");
        Response unavailable = reserve(UUID.randomUUID(), blocked, 1);
        assertEquals(409, unavailable.status(), unavailable.body());
        assertNull(reservation(orderId));

        Pool pool = pool(2);
        Response tooMany = orders.post(RESERVATIONS, "{\"orderId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + pool.productId() + "\",\"quantity\":11}",
                "Idempotency-Key", key());
        assertEquals(422, tooMany.status(), tooMany.body());
        Response notJson = orders.post(RESERVATIONS, "{\"orderId\":", "Idempotency-Key", key());
        assertEquals(400, notJson.status(), notJson.body());
        assertEquals(0, keys(pool.productId(), "reserved"));
    }

    // ---------------------------------------------------------------- срок

    @Test
    void theTimerReleasesAnExpiredReservationAndReturnsTheKeys() throws Exception {
        Pool pool = pool(5);
        UUID orderId = UUID.randomUUID();
        assertEquals(201, reserve(orderId, pool.productId(), 3).status());
        assertEquals("active", status(orderId));

        assertTrue(await(LIMIT, () -> "released".equals(status(orderId))), "таймер снял резерв после срока");
        assertEquals("expired", reservation(orderId).get("release_reason"));
        assertEquals(5, keys(pool.productId(), "free"));
        assertEquals(0, keys(pool.productId(), "reserved"));
        assertEquals(0, db.queryForObject("select count(*) from inventory.key where reservation_id is not null and product_id = ?", Integer.class,
                pool.productId()), "ключи отвязаны от снятого резерва");

        assertTrue(await(LIMIT, () -> !events(orderId, "reservation.expired").isEmpty()), "событие reservation.expired записано в Outbox");
        Map<String, Object> expired = events(orderId, "reservation.expired").get(0);
        assertEquals("timer", expired.get("expiredBy"), "срок обнаружил таймер, а не сверка");
        assertEquals(orderId.toString(), expired.get("orderId"));
        assertEquals(1, events(orderId, "reservation.expired").size());
        assertEquals(List.of(5, 2, 5), stockEvents(pool.productId()).stream().map(e -> e.get("available")).toList());
    }

    @Test
    void aReservationWhoseTimerIsLostIsReleasedByReconciliation() throws Exception {
        Pool pool = pool(5);
        UUID orderId = UUID.randomUUID();
        Response response = reserve(orderId, pool.productId(), 3);
        assertEquals(201, response.status(), response.body());
        String reservationId = (String) response.json().get("reservationId");
        assertTrue(redis.zrem(RedisExpiryQueue.KEY, reservationId), "таймер был поставлен в Redis после фиксации");
        assertTrue(redis.zrangeByScore(RedisExpiryQueue.KEY, 0, Long.MAX_VALUE, 1000).stream().noneMatch(reservationId::equals), "Redis потерял запись");

        assertTrue(await(LIMIT, () -> "released".equals(status(orderId))), "сверка по базе нашла просроченный резерв без таймера");
        assertEquals("expired", reservation(orderId).get("release_reason"));
        assertEquals(5, keys(pool.productId(), "free"));
        assertTrue(await(LIMIT, () -> !events(orderId, "reservation.expired").isEmpty()));
        assertEquals("reconciliation", events(orderId, "reservation.expired").get(0).get("expiredBy"));
    }

    // ---------------------------------------------------------------- отмена заказа

    @Test
    void orderCancelledReleasesTheReservationWithTheReasonAndARepeatChangesNothing() throws Exception {
        Pool pool = pool(5);
        UUID declined = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        assertEquals(201, reserve(declined, pool.productId(), 2).status());
        assertEquals(201, reserve(cancelled, pool.productId(), 1).status());
        assertEquals(3, keys(pool.productId(), "reserved"));

        UUID first = publishCancelled(declined, "payment_declined");
        assertTrue(await(LIMIT, () -> processed(first)), "событие обработано");
        assertEquals("released", status(declined));
        assertEquals("payment_declined", reservation(declined).get("release_reason"));
        assertEquals(1, keys(pool.productId(), "reserved"), "вернулись ключи только отменённого заказа");
        List<Map<String, Object>> released = events(declined, "reservation.released");
        assertEquals(1, released.size());
        assertEquals("payment_declined", released.get(0).get("reason"));

        long version = stockVersion(pool.productId());
        int outbox = stockEvents(pool.productId()).size();
        // то же событие ещё раз (дубль по event_id) и новое событие по тому же заказу (резерв уже снят): ничего не меняется
        send(first, declined, Map.of("orderId", declined.toString(), "cancelReason", "payment_declined"));
        UUID again = publishCancelled(declined, "order_cancelled");
        assertTrue(await(LIMIT, () -> processed(again)));
        assertEquals("payment_declined", reservation(declined).get("release_reason"), "причина первого снятия остаётся");
        assertEquals(1, events(declined, "reservation.released").size());
        assertEquals(version, stockVersion(pool.productId()));
        assertEquals(outbox, stockEvents(pool.productId()).size());

        UUID other = publishCancelled(cancelled, "order_cancelled");
        assertTrue(await(LIMIT, () -> processed(other)));
        assertEquals("order_cancelled", reservation(cancelled).get("release_reason"));
        assertEquals(5, keys(pool.productId(), "free"));
    }

    @Test
    void cancellationAfterTheReservationExpiredAndForAnUnknownOrderIsIgnored() throws Exception {
        Pool pool = pool(3);
        UUID orderId = UUID.randomUUID();
        assertEquals(201, reserve(orderId, pool.productId(), 1).status());
        assertTrue(await(LIMIT, () -> "released".equals(status(orderId))));
        long version = stockVersion(pool.productId());

        UUID late = publishCancelled(orderId, "payment_declined");
        UUID unknown = publishCancelled(UUID.randomUUID(), "order_cancelled");
        assertTrue(await(LIMIT, () -> processed(late) && processed(unknown)));
        assertEquals("expired", reservation(orderId).get("release_reason"), "причина истечения остаётся");
        assertEquals(0, events(orderId, "reservation.released").size());
        assertEquals(version, stockVersion(pool.productId()));
    }

    // ---------------------------------------------------------------- версия остатка

    @Test
    void stockVersionsGrowByOneWithoutGapsAcrossUploadsReservationsAndReleases() throws Exception {
        Pool pool = pool(4);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertEquals(201, reserve(first, pool.productId(), 2).status());
        assertEquals(200, batch(pool.seller(), pool.productId(), key(), key()).status());
        assertEquals(201, reserve(second, pool.productId(), 3).status());
        UUID cancel = publishCancelled(first, "order_cancelled");
        assertTrue(await(LIMIT, () -> processed(cancel)));
        assertEquals(409, reserve(UUID.randomUUID(), pool.productId(), 5).status(), "отказ версию не двигает");

        List<Map<String, Object>> events = stockEvents(pool.productId());
        assertEquals(List.of(4, 2, 4, 1, 3), events.stream().map(e -> e.get("available")).toList());
        long start = ((Number) events.get(0).get("version")).longValue();
        for (int i = 0; i < events.size(); i++) {
            assertEquals(start + i, ((Number) events.get(i).get("version")).longValue(), "версия события " + i + " растёт на единицу, без пропусков и повторов");
        }
        assertEquals(start + events.size() - 1, stockVersion(pool.productId()), "версия копии равна версии последнего события");
    }

    // ---------------------------------------------------------------- права вызывающих

    @Test
    void onlyOrderServiceMayReserve() throws Exception {
        Pool pool = pool(2);
        for (String caller : List.of("delivery-service", "payment-service", "api-gateway", "platform-service")) {
            UUID orderId = UUID.randomUUID();
            Response response = reserve(StandHttp.asService(caller, PORT), orderId, pool.productId(), 1, orderId.toString());
            assertEquals(403, response.status(), caller + ": " + response.body());
            assertEquals("forbidden", code(response));
            assertNull(reservation(orderId), caller + " ничего не зарезервировал");
        }
        assertEquals(0, keys(pool.productId(), "reserved"));
    }
}
