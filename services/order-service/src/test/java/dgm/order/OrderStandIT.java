package dgm.order;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import dgm.kit.testing.Stand;
import dgm.kit.testing.TestTokens;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Общая основа интеграционных тестов order-service на стенде: один запущенный сервис на все классы (второй экземпляр в том же процессе не
 * получится: публикатор Outbox держит блокировку лидера в базе, а у ролей базы есть пределы числа соединений), вызовы от имени
 * {@code api-gateway} по mTLS с токеном покупателя, прямое подключение к базе под ролью миграций для проверки результата.
 *
 * <p>Соседи саги (каталог, остатки, платежи) это {@link Neighbours}: один локальный HTTP-сервер в процессе теста. Так сценарии отказов
 * соседей (недоступен, нет ключей) повторяются точно, а проверка идёт по записанным вызовам. Сквозную работу с настоящими соседями проверяют
 * проверки К5 – К8 набора {@code full}.
 *
 * <p>Нужен набор стенда {@code dev-min} с отладочными портами ({@code make up SET=dev-min DEBUG=1}) и миграции базы заказов.
 */
@Tag("integration")
@SpringBootTest(classes = OrderServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("testdata")
@Import(OrderStandIT.TestBeans.class)
abstract class OrderStandIT {

    static final int PORT = StandHttp.freePort();
    static final int MANAGEMENT = StandHttp.freePort();
    static final TestTokens TOKENS = TestTokens.create(Clock.systemUTC());
    static final String ORDERS = "/api/v1/orders";

    /** Поднимается до старта приложения: адрес нужен свойствам. */
    static final Neighbours NEIGHBOURS = Neighbours.start();

    static StandHttp gateway;
    static StandHttp monitoring;
    static JdbcTemplate db;

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
        registry.add("dgm.peers.catalog-service.base-url", NEIGHBOURS::baseUrl);
        registry.add("dgm.peers.inventory-service.base-url", NEIGHBOURS::baseUrl);
        registry.add("dgm.peers.payment-service.base-url", NEIGHBOURS::baseUrl);
        // Предохранитель клиентов проверяют модульные тесты и сквозные проверки К7; здесь окно такое, что отказы разных тестов его не откроют
        registry.add("dgm.peers.catalog-service.breaker-window", () -> "1000");
        registry.add("dgm.peers.inventory-service.breaker-window", () -> "1000");
        registry.add("dgm.peers.payment-service.breaker-window", () -> "1000");
        // Сторож заказов в тестах не запускается сам: он отменил бы старые заказы тестовых данных и чужие заказы параллельных тестов.
        // Проход сторожа проверяют тесты явно, на выборке только своих заказов (OrderWatchdogIT)
        registry.add("dgm.order.watchdog.interval", () -> "PT1H");
        registry.add("dgm.order.watchdog.initial-delay", () -> "PT1H");
    }

    @BeforeAll
    static void connect() {
        gateway = StandHttp.asService("api-gateway", PORT);
        monitoring = StandHttp.asService("prometheus", MANAGEMENT);
        db = new JdbcTemplate(Stand.dataSource("migrator_order", "order_db"));
    }

    @BeforeEach
    void freshNeighbours() {
        NEIGHBOURS.reset();
    }

    // ------------------------------------------------------------------------------------------------ вызовы

    /** Токен покупателя: области создания и чтения заказов, подтверждённый телефон, адрес почты. */
    static String buyerToken(UUID buyer, String email) {
        return TOKENS.token().subject(buyer.toString()).scopes("orders.create", "orders.read").roles("buyer").amr("pwd").email(email).build();
    }

    static String buyerToken(UUID buyer) {
        return buyerToken(buyer, "buyer-" + buyer.toString().substring(0, 8) + "@mail.example");
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static Response create(String token, UUID product, int quantity, String idempotencyKey) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", product.toString());
        body.put("quantity", quantity);
        return gateway.post(ORDERS, Json.write(body), "Authorization", "Bearer " + token, "Idempotency-Key", idempotencyKey);
    }

    static Response create(String token, UUID product, int quantity) throws IOException {
        return create(token, product, quantity, key());
    }

    static Response read(String token, String path) throws IOException {
        return gateway.get(path, StandHttp.bearer(token));
    }

    static String code(Response response) {
        return (String) response.json().get("code");
    }

    /** Ждёт, пока условие выполнится: обработка событий идёт фоновым потребителем, итог видно только опросом базы. */
    static boolean await(Duration limit, BooleanSupplier condition) {
        long end = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    // ------------------------------------------------------------------------------------------------ база

    /** Строка заказа или {@code null}. */
    static Map<String, Object> order(String orderId) {
        List<Map<String, Object>> rows = db.queryForList("select id, number, buyer_id, seller_id, product_id, product_title, quantity, unit_price, amount, "
                + "commission_rate_bp, commission, currency, delivery_address, status, cancel_reason, reserve_until, session_until, payment_session_url, "
                + "created_at, paid_at, issued_at, version from orders.orders where id = ?", UUID.fromString(orderId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Статус заказа или {@code null}, если заказа нет. */
    static String status(String orderId) {
        Map<String, Object> row = order(orderId);
        return row == null ? null : (String) row.get("status");
    }

    static List<Map<String, Object>> ordersOf(UUID buyer) {
        return db.queryForList("select id, status, cancel_reason, product_id from orders.orders where buyer_id = ? order by created_at, id", buyer);
    }

    static long countOrders(UUID buyer, String... statuses) {
        String marks = String.join(", ", java.util.Collections.nCopies(statuses.length, "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(buyer);
        args.addAll(List.of(statuses));
        Long n = db.queryForObject("select count(*) from orders.orders where buyer_id = ? and status in (" + marks + ")", Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** События заказа в Outbox: тип, тема, тип агрегата, данные. */
    static List<Map<String, Object>> events(String orderId, String eventType) {
        return db.queryForList("select event_type, topic, aggregate_type, payload::text as payload from public.outbox where aggregate_id = ? and event_type = ? "
                + "order by id", orderId, eventType);
    }

    static long eventCount(String orderId) {
        Long n = db.queryForObject("select count(*) from public.outbox where aggregate_id = ?", Long.class, orderId);
        return n == null ? 0 : n;
    }

    static Map<String, Object> data(Map<String, Object> event) {
        return Json.parseObject((String) event.get("payload"));
    }
}
