package dgm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dgm.kit.json.Json;
import dgm.kit.route.RouteRule;
import dgm.kit.security.JwtDecoders;
import dgm.kit.security.JwtSettings;
import dgm.kit.testing.TestTokens;
import dgm.kit.time.Clocks;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/**
 * Шлюз целиком в Spring Boot: настоящие Spring Cloud Gateway, Netty и таблица маршрутов, а сервисы заменены заглушками на порту
 * localhost. TLS, Redis и Keycloak в тест не входят (профиль notls, свой ограничитель, ключ токенов теста): их проверяет стенд
 * ({@code tools/stand-checks/gateway_checks.py}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("notls")
class GatewayProxyTest {

    private static final TestTokens TOKENS = TestTokens.create(Clocks.system());
    private static final int GATEWAY_PORT = freePort();
    private static final Upstream CATALOG = Upstream.start();
    private static final Upstream ORDERS = Upstream.start();
    private static final int DEAD_PORT = freePort();     // порт, на котором никто не слушает: «сервис не запущен»
    private static final TestLimiter LIMITER = new TestLimiter();

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> GATEWAY_PORT);
        registry.add("dgm.gateway.upstreams.catalog-service", () -> "http://localhost:" + CATALOG.port());
        registry.add("dgm.gateway.upstreams.order-service", () -> "http://localhost:" + ORDERS.port());
        registry.add("dgm.gateway.upstreams.platform-service", () -> "http://localhost:" + DEAD_PORT);
        registry.add("dgm.security.jwt.issuer", () -> TestTokens.ISSUER);
        registry.add("dgm.security.jwt.jwks-uri", () -> "http://localhost:9/unused");
    }

    @TestConfiguration
    static class Beans {

        @Bean
        JwtDecoder jwtDecoder() {
            return JwtDecoders.forPublicKey(TOKENS.publicKey(), new JwtSettings(TestTokens.ISSUER, null), Clocks.system());
        }

        @Bean
        @Primary
        RequestLimiter testLimiter() {
            return LIMITER;
        }
    }

    @Autowired
    RouteTable table;

    @Autowired
    GatewayProperties properties;

    @BeforeEach
    void reset() {
        CATALOG.seen.clear();
        ORDERS.seen.clear();
        LIMITER.next = RequestLimiter.Decision.allow();
    }

    @AfterAll
    static void stop() {
        CATALOG.close();
        ORDERS.close();
    }

    // ---------------------------------------------------------------------------------------------------- заглушки

    /** Ограничитель теста: возвращает заданное решение. */
    static final class TestLimiter implements RequestLimiter {

        volatile Decision next = Decision.allow();

        @Override
        public Mono<Decision> check(String group, String key) {
            return Mono.just(next);
        }
    }

    /** Заглушка сервиса: запоминает запросы и отвечает JSON. */
    static final class Upstream implements AutoCloseable {

        record Seen(String method, String uri, com.sun.net.httpserver.Headers headers) {
        }

        final List<Seen> seen = new CopyOnWriteArrayList<>();
        private final HttpServer server;

        private Upstream(HttpServer server) {
            this.server = server;
        }

        static Upstream start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                Upstream upstream = new Upstream(server);
                server.createContext("/", exchange -> {
                    upstream.seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(), exchange.getRequestHeaders()));
                    byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.getResponseHeaders().add("ETag", "\"v1\"");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
                server.start();
                return upstream;
            } catch (IOException e) {
                throw new IllegalStateException("Заглушка сервиса не запущена", e);
            }
        }

        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Нет свободного порта", e);
        }
    }

    private static HttpResponse<String> call(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + GATEWAY_PORT + path)).timeout(Duration.ofSeconds(15))
                .method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String bearer(TestTokens.Spec spec) {
        return "Bearer " + spec.build();
    }

    // ---------------------------------------------------------------------------------------------------- проверки

    @Test
    void publicRouteIsProxiedWithTraceHeadersAndResponseIsPassedBack() throws Exception {
        HttpResponse<String> response = call("GET", "/api/v1/products?limit=5", "X-Forwarded-For", "1.2.3.4", "X-Seller-Id", "spoofed");

        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", response.body());
        assertEquals("\"v1\"", response.headers().firstValue("ETag").orElse(null), "заголовки сервиса доходят до клиента");
        assertTrue(response.headers().firstValue("X-Correlation-Id").isPresent());
        assertTrue(response.headers().firstValue("traceparent").isPresent());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));

        assertEquals(1, CATALOG.seen.size());
        Upstream.Seen seen = CATALOG.seen.get(0);
        assertEquals("GET", seen.method());
        assertEquals("/api/v1/products?limit=5", seen.uri(), "путь и параметры идут без изменений");
        assertEquals(response.headers().firstValue("X-Correlation-Id").orElse(null), seen.headers().getFirst("X-Correlation-Id"));
        assertNotNull(seen.headers().getFirst("traceparent"));
        assertNull(seen.headers().getFirst("X-Seller-Id"), "личность продавца клиент задавать не вправе");
        String forwardedFor = seen.headers().getFirst("X-Forwarded-For");
        assertNotNull(forwardedFor, "адрес источника выставляет шлюз");
        assertFalse(forwardedFor.contains("1.2.3.4"), "присланный клиентом адрес не принимается: " + forwardedFor);
        assertFalse(forwardedFor.contains(","), "одно значение, а не цепочка: " + forwardedFor);
        assertEquals("http", seen.headers().getFirst("X-Forwarded-Proto"));
        assertEquals("localhost:" + GATEWAY_PORT, seen.headers().getFirst("X-Forwarded-Host"));
        assertEquals(Integer.toString(GATEWAY_PORT), seen.headers().getFirst("X-Forwarded-Port"));
    }

    @Test
    void tokenRouteNeedsValidTokenAndPassesItUnchanged() throws Exception {
        HttpResponse<String> noToken = call("GET", "/api/v1/orders");
        assertEquals(401, noToken.statusCode());
        assertEquals("application/problem+json", noToken.headers().firstValue("Content-Type").orElse(null));
        assertEquals("unauthenticated", Json.parseObject(noToken.body()).get("code"));
        assertTrue(ORDERS.seen.isEmpty(), "без токена запрос до сервиса не доходит");

        String header = bearer(TOKENS.token());
        HttpResponse<String> ok = call("GET", "/api/v1/orders", "Authorization", header);
        assertEquals(200, ok.statusCode());
        assertEquals(1, ORDERS.seen.size());
        assertEquals(header, ORDERS.seen.get(0).headers().getFirst("Authorization"), "токен идёт сервису без изменений");
    }

    @Test
    void missingScopeIsRejectedByTheGateway() throws Exception {
        HttpResponse<String> response = call("POST", "/api/v1/orders", "Authorization", bearer(TOKENS.token().scopes("orders.read")));
        assertEquals(403, response.statusCode());
        assertEquals("forbidden", Json.parseObject(response.body()).get("code"));
        assertTrue(ORDERS.seen.isEmpty());
    }

    @Test
    void forgedTokenNeverReachesTheService() throws Exception {
        for (String token : List.of(TOKENS.token().buildUnsigned(), TOKENS.token().signedWith(TestTokens.newPair()).build(),
                TOKENS.token().expiresIn(Duration.ofMinutes(-10)).build())) {
            assertEquals(401, call("GET", "/api/v1/orders", "Authorization", "Bearer " + token).statusCode());
        }
        assertTrue(ORDERS.seen.isEmpty());
    }

    @Test
    void closedRoutesAreNotFoundAndNothingIsForwarded() throws Exception {
        String token = bearer(TOKENS.token());
        for (String path : List.of("/api/v1/unknown", "/internal/v1/products/0199e0a0-0000-4000-8000-000000000001", "/actuator/prometheus")) {
            HttpResponse<String> response = call("GET", path, "Authorization", token);
            assertEquals(404, response.statusCode(), path);
            assertEquals("not-found", Json.parseObject(response.body()).get("code"), path);
        }
        assertEquals(404, call("DELETE", "/api/v1/orders", "Authorization", token).statusCode());
        assertTrue(CATALOG.seen.isEmpty());
        assertTrue(ORDERS.seen.isEmpty());
    }

    @Test
    void limitExceededIsAnswered429WithRetryAfter() throws Exception {
        LIMITER.next = RequestLimiter.Decision.deny(9);
        HttpResponse<String> response = call("GET", "/api/v1/products");
        assertEquals(429, response.statusCode());
        assertEquals("9", response.headers().firstValue("Retry-After").orElse(null));
        assertEquals("rate-limited", Json.parseObject(response.body()).get("code"));
        assertTrue(CATALOG.seen.isEmpty());
    }

    @Test
    void stoppedServiceGivesProblemNotAStackTrace() throws Exception {
        HttpResponse<String> response = call("GET", "/api/v1/support-tickets", "Authorization",
                bearer(TOKENS.token().scopes("support.write")));
        assertEquals(503, response.statusCode());
        assertEquals("application/problem+json", response.headers().firstValue("Content-Type").orElse(null));
        assertEquals("dependency-unavailable", Json.parseObject(response.body()).get("code"));
        assertEquals("5", response.headers().firstValue("Retry-After").orElse(null));
        assertFalse(response.body().contains("localhost"), "адреса сервисов в ответ не попадают");
        assertFalse(response.body().contains(Integer.toString(DEAD_PORT)));
        assertTrue(response.headers().firstValue("X-Correlation-Id").isPresent());
    }

    @Test
    void everyRouteOfTheTableHasAnUpstreamAndALimitGroup() {
        assertFalse(table.rules().isEmpty());
        for (RouteRule rule : table.rules()) {
            RouteTable.Target target = table.target(rule.operationId()).orElseThrow();
            assertTrue(properties.getUpstreams().containsKey(target.service()), rule.operationId() + ": нет адреса сервиса " + target.service());
            assertTrue(properties.getLimits().containsKey(target.limit()), rule.operationId() + ": нет лимита группы " + target.limit());
        }
        for (String group : List.of("web", "files", "auth")) {
            assertTrue(properties.getLimits().containsKey(group), "нет лимита группы " + group + " для маршрутов вне OpenAPI");
        }
        for (String upstream : List.of("keycloak", "object-storage", "web-app", "external-stubs")) {
            assertTrue(properties.getUpstreams().containsKey(upstream), "нет адреса " + upstream);
        }
    }

    @Test
    void limitsFromTheDocumentAreTheOnesInAdr021() {
        assertEquals(60, properties.getLimits().get("public").getPerMinute());
        assertEquals(120, properties.getLimits().get("public").getBurst());
        assertEquals(10, properties.getLimits().get("order-create").getPerMinute());
        assertTrue(properties.getLimits().get("order-create").perUser());
        assertEquals(300, properties.getLimits().get("webhook").getPerMinute());
        assertEquals(30, properties.getLimits().get("auth").getPerMinute());
        assertEquals(Duration.ofMillis(200), properties.getLimiterTimeout());
        assertEquals(2 * 1024 * 1024, properties.getMaxBody().toBytes());
        assertEquals(64 * 1024, properties.getWebhookMaxBody().toBytes());
    }
}
