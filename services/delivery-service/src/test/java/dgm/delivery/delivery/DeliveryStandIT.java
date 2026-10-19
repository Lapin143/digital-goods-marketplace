package dgm.delivery.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.DeliveryServiceApplication;
import dgm.delivery.delivery.client.EmailClient;
import dgm.delivery.delivery.client.KeyClient;
import dgm.delivery.delivery.dispatcher.DeliveryDispatcher;
import dgm.delivery.delivery.letter.LetterBuilder;
import dgm.delivery.delivery.repository.DeliveryRepository;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StubsAdmin;
import dgm.kit.json.Json;
import dgm.kit.kafka.KafkaClients;
import dgm.kit.outbox.EnvelopeBuilder;
import dgm.kit.outbox.OutboxRow;
import dgm.kit.outbox.OutgoingEvent;
import dgm.kit.testing.Stand;
import dgm.kit.testing.TestTokens;
import dgm.kit.time.Timestamps;
import dgm.kit.trace.TraceContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Общая основа интеграционных тестов delivery-service на стенде: один запущенный сервис на все классы (публикатор Outbox держит блокировку
 * лидера в базе, у ролей базы есть пределы числа соединений), настоящие PostgreSQL, Kafka и почтовая заглушка {@code external-stubs}. Сервис
 * остатков это {@link InventoryStub} в процессе теста: ключи, отказы и счёт вызовов под управлением теста.
 *
 * <p>Фоновый диспетчер в тестах не работает: проход вызывает сам тест ({@link #drive}), иначе он забрал бы выдачу, которую тест держит «в
 * очереди», или чужие выдачи по {@code order.paid} прошлых тестов других сервисов (потребитель читает тему с начала). Расписание повторов
 * сжато до долей секунды, аренда 30 секунд (сорвавшийся проход в тестах изображает сам тест с арендой в две секунды).
 *
 * <p>Нужен набор стенда {@code dev-kit} с отладочными портами ({@code make up SET=dev-kit DEBUG=1}, заглушки на {@code 18443} и
 * {@code 18444}) и миграции базы выдачи ({@code make db-migrate S="delivery-service"}).
 */
@Tag("integration")
@SpringBootTest(classes = DeliveryServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(DeliveryStandIT.TestBeans.class)
abstract class DeliveryStandIT {

    static final int PORT = StandHttp.freePort();
    static final int MANAGEMENT = StandHttp.freePort();
    static final TestTokens TOKENS = TestTokens.create(Clock.systemUTC());
    static final InventoryStub INVENTORY = InventoryStub.start();
    static final Duration LIMIT = Duration.ofSeconds(60);
    static final String DOMAIN = "delivery-domain";

    static StubsAdmin stubs;
    static JdbcTemplate db;
    static KafkaProducer<String, String> orderEvents;
    static KafkaProducer<String, String> identityEvents;

    @Autowired
    DeliveryDispatcher dispatcher;
    @Autowired
    EmailClient emailClient;
    @Autowired
    KeyClient keyClient;
    @Autowired
    LetterBuilder letterBuilder;
    @Autowired
    DeliveryRepository repository;

    /** Оплаченный заказ. */
    record Placed(UUID orderId, UUID buyerId, String email, String title, int quantity) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        JwtDecoder jwtDecoder() {
            return StandHttp.decoderFor(TOKENS);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        StandHttp.register(registry, PORT, MANAGEMENT);
        registry.add("dgm.peers.inventory-service.base-url", INVENTORY::baseUrl);
        // Предохранитель клиента проверяют модульные тесты и сквозные проверки К7; здесь окно такое, что отказы разных тестов его не откроют
        registry.add("dgm.peers.inventory-service.breaker-window", () -> "1000");
        // Почта: заглушка набора stubs с хоста (отладочный порт приложения), ключ API из каталога секретов стенда
        registry.add("dgm.email.base-url", () -> "https://localhost:" + env("DGM_STUBS_PORT", "18443"));
        // Диспетчер в тестах не запускается сам: проход вызывает тест
        registry.add("dgm.delivery.dispatcher.interval", () -> "PT1H");
        registry.add("dgm.delivery.dispatcher.initial-delay", () -> "PT1H");
        registry.add("dgm.delivery.dispatcher.lease", () -> "PT30S");
        // Шесть попыток за долю секунды: настоящие паузы 10 с … 10 минут проверяет модульный тест расписания
        registry.add("delivery.retry-schedule", () -> "200ms,200ms,200ms,200ms,200ms");
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeAll
    static void connect() {
        db = new JdbcTemplate(Stand.dataSource("migrator_delivery", "delivery_db"));
        stubs = StubsAdmin.onStand();
        orderEvents = KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("order-service"), "order-service-delivery-it");
        identityEvents = KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("platform-service"), "platform-service-delivery-it");
    }

    @AfterAll
    static void disconnect() {
        orderEvents.close(Duration.ofSeconds(5));
        identityEvents.close(Duration.ofSeconds(5));
    }

    @BeforeEach
    void fresh() {
        INVENTORY.reset();
        stubs.reset();
        emailMode(Map.of());
    }

    /** Режим почтовой заглушки: статусов вебхуком нет (шлюз платформы в наборе не поднят), остальное задаёт тест. */
    static void emailMode(Map<String, ?> fields) {
        Map<String, Object> mode = new LinkedHashMap<>(fields);
        mode.put("status", "none");
        stubs.setMode("email", mode);
    }

    // ------------------------------------------------------------------------------------------------ события

    static UUID publish(KafkaProducer<String, String> producer, String source, String topic, String aggregateType, String type, UUID eventId,
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

    static Map<String, Object> money(long amount) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", "RUB");
        return money;
    }

    static Map<String, Object> paidData(Placed p, Instant paidAt) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("channel", "email");
        delivery.put("address", p.email());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", p.orderId().toString());
        data.put("orderNumber", 1001);
        data.put("buyerId", p.buyerId().toString());
        data.put("sellerId", UUID.randomUUID().toString());
        data.put("productId", UUID.randomUUID().toString());
        data.put("productTitle", p.title());
        data.put("quantity", p.quantity());
        data.put("total", money(149_900L * p.quantity()));
        data.put("commission", money(2_998L * p.quantity()));
        data.put("paidAt", Timestamps.format(paidAt));
        data.put("latePayment", false);
        data.put("delivery", delivery);
        return data;
    }

    /** Новый оплаченный заказ (данные для события) с уникальным адресом и названием. */
    static Placed newOrder(UUID buyerId, int quantity) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return new Placed(UUID.randomUUID(), buyerId, "buyer-" + tag + "@mail.example", "Игра " + tag, quantity);
    }

    static Placed newOrder(int quantity) {
        return newOrder(UUID.randomUUID(), quantity);
    }

    /** {@code order.paid} от order-service. */
    static UUID publishPaid(Placed p, Instant paidAt, UUID eventId) throws Exception {
        return publish(orderEvents, "order-service", "order.events", "Order", "order.paid", eventId, p.orderId().toString(), paidData(p, paidAt));
    }

    /** Оплаченный заказ приходит событием; метод ждёт, пока потребитель создаст первичную выдачу. */
    Placed place(UUID buyerId, int quantity) throws Exception {
        Placed p = newOrder(buyerId, quantity);
        publishPaid(p, Instant.now().truncatedTo(ChronoUnit.MILLIS), UUID.randomUUID());
        assertTrue(await(LIMIT, () -> deliveryOf(p) != null), "выдача заказа " + p.orderId() + " не создана по order.paid");
        return p;
    }

    Placed place(int quantity) throws Exception {
        return place(UUID.randomUUID(), quantity);
    }

    /** Выдача, созданная без Kafka (для гонок: быстро и без зависимости от отставания потребителя), срок наступил вчера (такие берутся раньше выдач, которые тест не создавал). */
    Placed seed(int quantity) {
        Placed p = newOrder(quantity);
        db.update("insert into delivery.delivery (id, order_id, buyer_id, kind, address, product_title, quantity, next_attempt_at) "
                + "values (?, ?, ?, 'primary', ?, ?, ?, now() - interval '1 day')", UUID.randomUUID(), p.orderId(), p.buyerId(), p.email(), p.title(),
                p.quantity());
        return p;
    }

    // ------------------------------------------------------------------------------------------------ проход диспетчера

    /** Вызывает проходы диспетчера, пока условие не выполнится или не кончится время. */
    boolean drive(Duration limit, BooleanSupplier done) {
        long end = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < end) {
            if (done.getAsBoolean()) {
                return true;
            }
            dispatcher.runOnce();
            pause(100);
        }
        return done.getAsBoolean();
    }

    /** Ждёт, пока условие выполнится: обработка событий идёт фоновым потребителем. */
    static boolean await(Duration limit, BooleanSupplier condition) {
        long end = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            pause(100);
        }
        return condition.getAsBoolean();
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------------ база

    /** Первичная выдача заказа или {@code null}. */
    static Map<String, Object> deliveryOf(Placed p) {
        List<Map<String, Object>> rows = db.queryForList("select id, order_id, buyer_id, kind, channel, address, product_title, quantity, attempt_no, status, "
                + "last_error_code, fail_reason, (next_attempt_at is not null) as scheduled from delivery.delivery where order_id = ? and kind = 'primary'",
                p.orderId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    static String status(Placed p) {
        Map<String, Object> row = deliveryOf(p);
        return row == null ? null : (String) row.get("status");
    }

    static int attemptNo(Placed p) {
        Map<String, Object> row = deliveryOf(p);
        return row == null ? -1 : ((Number) row.get("attempt_no")).intValue();
    }

    static UUID idOf(Placed p) {
        return (UUID) deliveryOf(p).get("id");
    }

    static List<Map<String, Object>> attempts(Placed p) {
        return db.queryForList("select attempt_no, outcome, error_code from delivery.delivery_attempt where delivery_id = ? order by attempt_no", idOf(p));
    }

    static List<Map<String, Object>> events(Placed p, String eventType) {
        return db.queryForList("select event_type, topic, aggregate_type, payload::text as payload from public.outbox where aggregate_id = ? and event_type = ? "
                + "order by id", p.orderId().toString(), eventType);
    }

    static Map<String, Object> data(Map<String, Object> event) {
        return Json.parseObject((String) event.get("payload"));
    }

    static boolean processed(UUID eventId) {
        Integer n = db.queryForObject("select count(*) from public.processed_event where consumer = ? and event_id = ?", Integer.class, DOMAIN, eventId);
        return n != null && n > 0;
    }

    /** Сколько строк во всех таблицах сервиса, где значение встречается в записи целиком (INV-20: значений ключей в базе нет). */
    static long occurrences(String value) {
        String like = "%" + value + "%";
        long total = 0;
        for (String table : List.of("delivery.delivery", "delivery.delivery_attempt", "delivery.delivery_watch", "delivery.provider_status_event", "public.outbox",
                "public.processed_event")) {
            Long n = db.queryForObject("select count(*) from " + table + " t where t::text like ?", Long.class, like);
            total += n == null ? 0 : n;
        }
        return total;
    }

    /** Письма заглушки получателю. */
    static List<Map<String, Object>> mails(Placed p) {
        return stubs.emails(p.email(), "delivery", null);
    }

    static void assertSent(Placed p, List<String> keys) {
        List<Map<String, Object>> mails = mails(p);
        assertEquals(1, mails.size(), "письмо получателю " + p.email());
        String text = (String) mails.get(0).get("text");
        for (String key : keys) {
            assertTrue(text.contains(key), "в письме нет ключа");
        }
    }
}
