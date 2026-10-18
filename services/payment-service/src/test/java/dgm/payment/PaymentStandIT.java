package dgm.payment;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.boot.testing.StubsAdmin;
import dgm.kit.json.Json;
import dgm.kit.testing.Stand;
import dgm.kit.testing.TestTokens;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Общая основа интеграционных тестов payment-service на стенде: один запущенный сервис на все классы (второй экземпляр в том же
 * процессе не получится: публикатор Outbox держит блокировку лидера в базе, а у ролей базы есть пределы числа соединений), вызовы
 * {@code order-service} по mTLS, прямое подключение к базе под ролью миграций и управление заглушкой шлюза.
 *
 * <p>Нужен набор стенда {@code dev-kit} с отладочными портами ({@code make up SET=dev-kit DEBUG=1}, заглушки на {@code 18443} и
 * {@code 18444}) и миграции базы платежей ({@code make db-migrate S="payment-service"}). Перед каждым тестом заглушка сбрасывается и
 * переводится в режим «ждать команды»: исхода платежа сам по себе не наступает, вебхуков на несуществующий в наборе шлюз платформы нет.
 */
@Tag("integration")
@SpringBootTest(classes = PaymentServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(PaymentStandIT.TestBeans.class)
abstract class PaymentStandIT {

    static final int PORT = StandHttp.freePort();
    static final int MANAGEMENT = StandHttp.freePort();
    static final TestTokens TOKENS = TestTokens.create(Clock.systemUTC());
    static final String SESSIONS = "/internal/v1/payment-sessions";
    static final Duration LIMIT = Duration.ofSeconds(60);

    static StandHttp orders;
    static StandHttp delivery;
    static JdbcTemplate db;
    static StubsAdmin stubs;

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
        // Заглушка шлюза набора stubs с хоста: отладочный порт приложения (платёжное API)
        registry.add("dgm.peers.payment-gateway.base-url", () -> env("DGM_STUBS_API", "https://localhost:18443"));
        // Предохранитель клиента шлюза проверяют модульные тесты и сквозные проверки К7; здесь окно такое, что отказы разных тестов его не откроют
        registry.add("dgm.peers.payment-gateway.breaker-window", () -> "1000");
        // Запрос статуса внутри вебхука идёт по отдельному узлу (одна попытка, без повторов): тот же шлюз и то же окно предохранителя
        registry.add("dgm.peers.payment-gateway-status.base-url", () -> env("DGM_STUBS_API", "https://localhost:18443"));
        registry.add("dgm.peers.payment-gateway-status.breaker-window", () -> "1000");
        // Сверка и сопоставление уведомлений сами в тестах не запускаются: проходы вызываются явно, иначе фоновый проход подтвердил бы платёж,
        // который тест держит в статусе «создан»
        registry.add("dgm.payment.reconcile.initial-delay", () -> "PT1H");
        registry.add("dgm.payment.reconcile.old-initial-delay", () -> "PT1H");
        registry.add("dgm.payment.reconcile.unmatched-initial-delay", () -> "PT1H");
    }

    @BeforeAll
    static void connect() {
        orders = StandHttp.asService("order-service", PORT);
        delivery = StandHttp.asService("delivery-service", PORT);
        db = new JdbcTemplate(Stand.dataSource("migrator_payment", "payment_db"));
        stubs = StubsAdmin.onStand();
    }

    @BeforeEach
    void freshGateway() {
        stubs.reset();
        stubs.setMode("payment", Map.of("scenario", "hold"));
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    /** Тело {@code OpenPaymentSessionRequest}. */
    static String openBody(UUID orderId, long amount, Instant sessionExpiresAt) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", "RUB");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId.toString());
        body.put("amount", money);
        body.put("sessionExpiresAt", dgm.kit.time.Timestamps.format(sessionExpiresAt));
        return Json.write(body);
    }

    /** Срок сессии по умолчанию: 12 минут от сейчас, как у заказа. */
    static Instant sessionEnd() {
        return Instant.now().plus(Duration.ofMinutes(12));
    }

    /**
     * Срок сессии по заказу: {@code order-service} присылает для заказа один и тот же срок при каждом вызове, и шлюз отвергает повтор создания
     * платежа с тем же ключом и другим телом (409 {@code idempotency_conflict}). Поэтому срок запоминается по заказу, а не по вызову: двадцать
     * вызовов гонки с разными ключами идемпотентности несут одно и то же тело. Тот же срок держит повтор с одним ключом, иначе охранник ответил бы
     * 422 {@code idempotency-key-reuse}.
     */
    private static final Map<UUID, Instant> SESSION_ENDS = new java.util.concurrent.ConcurrentHashMap<>();

    static Response open(StandHttp caller, UUID orderId, long amount, String idempotencyKey) throws IOException {
        Instant end = SESSION_ENDS.computeIfAbsent(orderId, k -> sessionEnd());
        return caller.post(SESSIONS, openBody(orderId, amount, end), "Idempotency-Key", idempotencyKey);
    }

    /** Вызов от имени {@code order-service} с новым ключом идемпотентности. */
    static Response open(UUID orderId, long amount) throws IOException {
        return open(orders, orderId, amount, key());
    }

    static String code(Response response) {
        return (String) response.json().get("code");
    }

    /** Строка платежа заказа или {@code null}. */
    static Map<String, Object> payment(UUID orderId) {
        List<Map<String, Object>> rows = db.queryForList("select id, gateway_payment_id, session_url, session_expires_at, amount, currency, status, version "
                + "from payments.payment where order_id = ?", orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static int paymentsOf(UUID orderId) {
        Integer n = db.queryForObject("select count(*) from payments.payment where order_id = ?", Integer.class, orderId);
        return n == null ? 0 : n;
    }

    /** Платежи заглушки по заказу. */
    static List<Map<String, Object>> gatewayPaymentsOf(UUID orderId) {
        return stubs.payments().stream().filter(p -> orderId.toString().equals(p.get("orderId"))).toList();
    }

    static boolean await(Duration limit, BooleanSupplier condition) {
        long end = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
