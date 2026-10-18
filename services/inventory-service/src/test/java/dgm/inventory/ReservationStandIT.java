package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Общие помощники интеграционных тестов резерва и выдачи: вызовы {@code order-service} и {@code delivery-service} по mTLS, товар с
 * пулом ключей, запросы к таблицам резервов и ключей, события {@code order.cancelled} от имени {@code order-service}. Срок резерва
 * 6 секунд, таймер и сверка просыпаются часто (свойства в {@link InventoryStandIT}).
 */
abstract class ReservationStandIT extends KeyStandIT {

    static final String RESERVATIONS = "/internal/v1/reservations";
    static final String CONFIRM = RESERVATIONS + "/confirm";
    static final String KEY_VALUES = "/internal/v1/keys";
    static final Duration LIMIT = Duration.ofSeconds(60);

    static StandHttp orders;
    static StandHttp delivery;
    static KafkaProducer<String, String> orderEvents;

    @Autowired
    KitRedis redis;

    @BeforeAll
    static void clients() {
        orders = StandHttp.asService("order-service", PORT);
        delivery = StandHttp.asService("delivery-service", PORT);
        orderEvents = KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("order-service"), "order-service-it");
    }

    @AfterAll
    static void close() {
        orderEvents.close(Duration.ofSeconds(5));
    }

    /** Товар продавца с пулом ключей, загруженных через шлюз; значения известны тесту. */
    record Pool(Seller seller, UUID productId, List<String> values) {
    }

    static Pool pool(int count) throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product("published", "key_pool");
        String[] keys = new String[count];
        for (int i = 0; i < count; i++) {
            keys[i] = key();
        }
        Response loaded = batch(seller, productId, keys);
        assertEquals(200, loaded.status(), loaded.body());
        return new Pool(seller, productId, List.of(keys));
    }

    static String body(UUID orderId, UUID productId, int quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId.toString());
        body.put("productId", productId.toString());
        body.put("quantity", quantity);
        return Json.write(body);
    }

    static Response reserve(StandHttp caller, UUID orderId, UUID productId, int quantity, String idempotencyKey) throws IOException {
        return caller.post(RESERVATIONS, body(orderId, productId, quantity), "Idempotency-Key", idempotencyKey);
    }

    /** Вызов саги: ключ идемпотентности равен идентификатору заказа. */
    static Response reserve(UUID orderId, UUID productId, int quantity) throws IOException {
        return reserve(orders, orderId, productId, quantity, orderId.toString());
    }

    static Response confirm(StandHttp caller, UUID orderId, UUID productId, int quantity, String idempotencyKey) throws IOException {
        return caller.post(CONFIRM, body(orderId, productId, quantity), "Idempotency-Key", idempotencyKey);
    }

    /** Подтверждение от имени саги с новым ключом идемпотентности: повтор тела под тем же ключом проверяют отдельные тесты. */
    static Response confirm(UUID orderId, UUID productId, int quantity) throws IOException {
        return confirm(orders, orderId, productId, quantity, key());
    }

    static Response keyValues(StandHttp caller, UUID orderId) throws IOException {
        return caller.get(KEY_VALUES + "?orderId=" + orderId);
    }

    /** Последний резерв заказа или {@code null}. */
    static Map<String, Object> reservation(UUID orderId) {
        List<Map<String, Object>> rows = db.queryForList("select id, status, quantity, release_reason, expires_at, created_at, used_at from inventory.reservation "
                + "where order_id = ? order by created_at desc, id desc", orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static String status(UUID orderId) {
        Map<String, Object> row = reservation(orderId);
        return row == null ? null : (String) row.get("status");
    }

    static int reservationsOf(UUID orderId) {
        Integer n = db.queryForObject("select count(*) from inventory.reservation where order_id = ?", Integer.class, orderId);
        return n == null ? 0 : n;
    }

    static int keys(UUID productId, String status) {
        Integer n = db.queryForObject("select count(*) from inventory.key where product_id = ? and status = ?", Integer.class, productId, status);
        return n == null ? 0 : n;
    }

    static int keysOfOrder(UUID orderId, String status) {
        Integer n = db.queryForObject("select count(*) from inventory.key where order_id = ? and status = ?", Integer.class, orderId, status);
        return n == null ? 0 : n;
    }

    static List<Map<String, Object>> events(UUID aggregateId, String type) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String payload : db.queryForList("select payload::text from public.outbox where aggregate_id = ? and event_type = ? order by id", String.class,
                aggregateId.toString(), type)) {
            events.add(Json.parseObject(payload));
        }
        return events;
    }

    static long stockVersion(UUID productId) {
        Long version = db.queryForObject("select stock_version from inventory.product_copy where product_id = ?", Long.class, productId);
        return version == null ? 0 : version;
    }

    /** Публикует {@code order.cancelled} от имени order-service (права Kafka выданы по CN сертификата). Ключ записи равен идентификатору заказа. */
    static UUID publishCancelled(UUID orderId, String cancelReason) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId.toString());
        data.put("buyerId", UUID.randomUUID().toString());
        data.put("cancelReason", cancelReason);
        UUID eventId = UUID.randomUUID();
        send(eventId, orderId, data);
        return eventId;
    }

    static void send(UUID eventId, UUID orderId, Map<String, Object> data) throws Exception {
        String headers = "{\"schemaversion\": 1, \"traceparent\": \"" + TraceContext.fresh().traceparent() + "\", \"correlationid\": \"" + UUID.randomUUID()
                + "\"}";
        OutgoingEvent event = new EnvelopeBuilder("order-service").build(new OutboxRow(1, eventId, "Order", orderId.toString(), "order.cancelled",
                "order.events", Json.write(data), headers, Instant.now(), 0));
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.value());
        record.headers().add("ce_type", event.type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent", event.traceparent().getBytes(StandardCharsets.UTF_8));
        orderEvents.send(record).get(20, TimeUnit.SECONDS);
    }

    static boolean processed(UUID eventId) {
        Integer n = db.queryForObject("select count(*) from public.processed_event where consumer = 'reservation-service' and event_id = ?", Integer.class,
                eventId);
        return n != null && n > 0;
    }
}
