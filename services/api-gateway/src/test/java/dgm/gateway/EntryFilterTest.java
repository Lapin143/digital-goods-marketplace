package dgm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.security.JwtDecoders;
import dgm.kit.security.JwtSettings;
import dgm.kit.testing.TestTokens;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Единственный фильтр шлюза целиком, без сети: путь, размер тела, токен (ST-03: подделки отклоняются), области, второй фактор, сессия по
 * SMS, лимит частоты и отказ открытым, очистка внешних заголовков, сквозные идентификаторы.
 */
class EntryFilterTest {

    private static final Set<String> UPSTREAMS = Set.of("catalog-service", "inventory-service", "order-service", "payment-service",
            "delivery-service", "platform-service", "keycloak", "object-storage", "web-app", "external-stubs");
    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final String CORRELATION = "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b";

    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-05T10:00:00Z"));
    private final TestTokens tokens = TestTokens.create(clock);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GatewayProperties properties = properties();
    private final ScriptedLimiter limiter = new ScriptedLimiter();
    private final RouteTable routes = RouteTable.fromClasspath("dgm/gateway-routes.json", UPSTREAMS);
    private JwtDecoder decoder = JwtDecoders.forPublicKey(tokens.publicKey(), new JwtSettings(TestTokens.ISSUER, null), clock);

    // ---------------------------------------------------------------------------------------------------- обвязка

    private static GatewayProperties properties() {
        GatewayProperties p = new GatewayProperties();
        for (String group : List.of("public", "web", "files", "auth", "webhook")) {
            p.getLimits().put(group, limit("ip"));
        }
        for (String group : List.of("buyer", "order-create", "account", "seller-apply", "seller", "staff")) {
            p.getLimits().put(group, limit("user"));
        }
        p.setLimiterTimeout(Duration.ofMillis(50));
        return p;
    }

    private static GatewayProperties.Limit limit(String key) {
        GatewayProperties.Limit limit = new GatewayProperties.Limit();
        limit.setKey(key);
        return limit;
    }

    /** Ограничитель с заданным ответом; запоминает группы и ключи вызовов. */
    private static final class ScriptedLimiter implements RequestLimiter {

        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile Supplier<Mono<Decision>> next = () -> Mono.just(Decision.allow());

        @Override
        public Mono<Decision> check(String group, String key) {
            calls.add(group + "|" + key);
            return next.get();
        }
    }

    private EntryFilter filter(int managementPort) {
        return new EntryFilter(routes, new TokenVerifier(decoder), limiter, properties, meters, clock, managementPort);
    }

    private record Result(MockServerWebExchange exchange, ServerWebExchange forwarded) {

        boolean passed() {
            return forwarded != null;
        }

        int status() {
            return exchange.getResponse().getStatusCode().value();
        }

        String body() {
            return exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5));
        }

        String code() {
            return (String) Json.parseObject(body()).get("code");
        }

        String header(String name) {
            return exchange.getResponse().getHeaders().getFirst(name);
        }

        String forwardedHeader(String name) {
            return forwarded.getRequest().getHeaders().getFirst(name);
        }
    }

    private Result run(MockServerHttpRequest request) {
        return run(filter(0), MockServerWebExchange.from(request));
    }

    private static Result run(EntryFilter filter, MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            forwarded.set(ex);
            return ex.getResponse().setComplete();
        };
        filter.filter(exchange, chain).block(Duration.ofSeconds(5));
        return new Result(exchange, forwarded.get());
    }

    private Result get(String path, String token) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return run(request.build());
    }

    private Result post(String path, String token) {
        return run(MockServerHttpRequest.post(path).header("Authorization", "Bearer " + token).build());
    }

    private double count(String reason) {
        var counter = meters.find("dgm.gateway.rejected").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---------------------------------------------------------------------------------------------------- маршруты

    @Test
    void publicRouteGoesThroughWithoutToken() {
        Result r = get("/api/v1/products", null);
        assertTrue(r.passed());
        assertEquals("catalog-service", ((RouteTable.Target) r.forwarded().getAttribute(EntryFilter.TARGET)).service());
        assertEquals(List.of("public|ip:unknown"), limiter.calls);
    }

    @Test
    void unknownPathAndInternalPathAreNotFound() {
        for (String path : List.of("/api/v1/unknown", "/internal/v1/products/0199e0a0-0000-4000-8000-000000000001", "/api/v2/orders")) {
            Result r = get(path, tokens.token().build());
            assertFalse(r.passed(), path);
            assertEquals(404, r.status(), path);
            assertEquals("not-found", r.code(), path);
        }
        assertTrue(limiter.calls.isEmpty(), "закрытый маршрут не доходит до ограничителя");
    }

    @Test
    void wrongMethodIsNotFound() {
        Result r = run(MockServerHttpRequest.delete("/api/v1/orders").build());
        assertEquals(404, r.status());
    }

    @Test
    void trickyPathsAreRejectedAsBadRequest() {
        for (String path : List.of("/api/v1/orders/%2e%2e/x", "/api/v1/orders;jsessionid=1", "/api//v1/orders", "/internal/%2e/v1/x",
                "/assets/../secret", "/files/a%2Fb")) {
            Result r = run(MockServerHttpRequest.method(HttpMethod.GET, URI.create(path)).build());
            assertFalse(r.passed(), path);
            assertEquals(400, r.status(), path);
            assertEquals("bad-request", r.code(), path);
        }
    }

    @Test
    void pathSafetyRules() {
        assertTrue(EntryFilter.safe("/"));
        assertTrue(EntryFilter.safe("/api/v1/orders"));
        assertTrue(EntryFilter.safe("/assets/app.js"));
        assertTrue(EntryFilter.safe("/files/seller-documents/x%20y"));
        assertFalse(EntryFilter.safe("/api/v1/orders;x=1"));
        assertFalse(EntryFilter.safe("/api/v1/orders/%41"));
        assertFalse(EntryFilter.safe("/assets/%2E%2E/x"));
        assertFalse(EntryFilter.safe("/assets/.."));
        assertFalse(EntryFilter.safe("/a\\b"));
        assertFalse(EntryFilter.safe(""));
        assertFalse(EntryFilter.safe("api"));
    }

    // ---------------------------------------------------------------------------------------------------- размер тела

    @Test
    void bodyLargerThanTwoMegabytesIsRejectedBeforeTheTokenCheck() {
        Result r = run(MockServerHttpRequest.post("/api/v1/orders").header("Content-Length", String.valueOf(2 * 1024 * 1024 + 1)).build());
        assertEquals(413, r.status());
        assertEquals("payload-too-large", r.code());
    }

    @Test
    void bodyOfTwoMegabytesPasses() {
        Result r = run(MockServerHttpRequest.post("/api/v1/seller/products/0199e0a0-0000-4000-8000-000000000001/key-files")
                .header("Authorization", "Bearer " + tokens.token().scopes("seller.catalog").roles("seller").amr("pwd", "otp").build())
                .header("Content-Length", String.valueOf(2 * 1024 * 1024)).build());
        assertTrue(r.passed());
    }

    @Test
    void webhookBodyIsLimitedToSixtyFourKilobytes() {
        Result tooBig = run(MockServerHttpRequest.post("/api/v1/webhooks/payment-gateway").header("Content-Length", "65537").build());
        assertEquals(413, tooBig.status());
        Result fine = run(MockServerHttpRequest.post("/api/v1/webhooks/payment-gateway").header("Content-Length", "65536").build());
        assertTrue(fine.passed());
        assertEquals(RouteTable.Kind.WEBHOOK, ((RouteTable.Target) fine.forwarded().getAttribute(EntryFilter.TARGET)).kind());
    }

    // ---------------------------------------------------------------------------------------------------- токен (ST-03)

    @Test
    void validTokenPassesAndIsForwardedUnchanged() {
        String token = tokens.token().build();
        Result r = get("/api/v1/orders", token);
        assertTrue(r.passed());
        assertEquals("Bearer " + token, r.forwardedHeader("Authorization"));
        assertEquals(List.of("buyer|u:" + USER), limiter.calls, "лимит считается на пользователя из токена");
    }

    @Test
    void missingTokenIsUnauthenticatedWithChallenge() {
        Result r = get("/api/v1/orders", null);
        assertEquals(401, r.status());
        assertEquals("unauthenticated", r.code());
        assertEquals("Bearer", r.header("WWW-Authenticate"));
        assertEquals("application/problem+json", r.header("Content-Type"));
    }

    @Test
    void otherSchemeAndEmptyTokenAreUnauthenticated() {
        for (String header : List.of("Basic dXNlcjpwYXNz", "Bearer", "Bearer ", "Token abc")) {
            Result r = run(MockServerHttpRequest.get("/api/v1/orders").header("Authorization", header).build());
            assertEquals(401, r.status(), header);
        }
    }

    @Test
    void forgedTokensAreRejected() {
        String valid = tokens.token().scopes("orders.read").build();
        String[] parts = valid.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String widened = payload.replace("\"scope\":\"orders.read\"", "\"scope\":\"orders.read orders.create\"");
        assertNotEquals(payload, widened, "в тесте область должна измениться");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(widened.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        List<String> forged = List.of(
                "not-a-token",
                tokens.token().buildUnsigned(),                                    // алгоритм none
                tokens.token().signedWith(TestTokens.newPair()).build(),           // чужой ключ
                tokens.token().issuer("https://evil.example/auth/realms/dgm").build(),   // чужой издатель
                tokens.token().audience("other-api").build(),                      // чужой адресат
                tokens.token().expiresIn(Duration.ofMinutes(-10)).build(),         // просрочен
                tampered);                                                          // область дописана после подписи
        for (String token : forged) {
            Result r = post("/api/v1/orders", token);
            assertFalse(r.passed(), token);
            assertEquals(401, r.status(), token);
            assertEquals("unauthenticated", r.code(), token);
            assertEquals("Bearer error=\"invalid_token\"", r.header("WWW-Authenticate"), token);
        }
        assertEquals(forged.size(), count("unauthenticated"));
        assertTrue(limiter.calls.isEmpty(), "отклонённый запрос не тратит лимит");
    }

    @Test
    void unreachableKeysGiveServiceUnavailableNotUnauthenticated() {
        decoder = token -> {
            throw new JwtException("ключи Keycloak не получены");
        };
        Result r = get("/api/v1/orders", tokens.token().build());
        assertEquals(503, r.status());
        assertEquals("dependency-unavailable", r.code());
        assertEquals("5", r.header("Retry-After"));
        assertEquals(1, count("token_unavailable"));
    }

    // ---------------------------------------------------------------------------------------------------- доступ по области, 2FA, SMS

    @Test
    void missingScopeIsForbidden() {
        Result r = post("/api/v1/orders", tokens.token().scopes("orders.read").build());
        assertEquals(403, r.status());
        assertEquals("forbidden", r.code());
        assertTrue(r.body().contains("orders.create"));
    }

    @Test
    void sellerWithoutSecondFactorIsRejectedAndWithItPasses() {
        String withoutOtp = tokens.token().scopes("seller.catalog").roles("seller").amr("pwd").build();
        Result denied = get("/api/v1/seller/products", withoutOtp);
        assertEquals(403, denied.status());
        assertEquals("second-factor-required", denied.code());

        String withOtp = tokens.token().scopes("seller.catalog").roles("seller").amr("pwd", "otp").build();
        assertTrue(get("/api/v1/seller/products", withOtp).passed());
    }

    @Test
    void staffRoutesNeedSecondFactorToo() {
        String token = tokens.token().scopes("staff.admin").roles("admin").amr("pwd").build();
        assertEquals("second-factor-required", get("/api/v1/staff/parameters", token).code());
    }

    @Test
    void smsSessionCannotDoFullLoginActions() {
        Result r = post("/api/v1/orders", tokens.token().scopes("orders.create").amr("sms").build());
        assertEquals(403, r.status());
        assertEquals("full-login-required", r.code());
        // чтение заказов сессии по SMS доступно
        assertTrue(get("/api/v1/orders", tokens.token().scopes("orders.read").amr("sms").build()).passed());
    }

    // ---------------------------------------------------------------------------------------------------- лимит частоты

    @Test
    void limitExceededGivesTooManyRequestsWithRetryAfter() {
        limiter.next = () -> Mono.just(RequestLimiter.Decision.deny(9));
        Result r = get("/api/v1/products", null);
        assertFalse(r.passed());
        assertEquals(429, r.status());
        assertEquals("rate-limited", r.code());
        assertEquals("9", r.header("Retry-After"));
        assertEquals(1, count("rate_limited"));
    }

    @Test
    void publicRoutesAreCountedPerAddress() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/products")
                .remoteAddress(new InetSocketAddress(InetAddress.getByName("203.0.113.5"), 4000)).build();
        run(request);
        assertEquals(List.of("public|ip:203.0.113.5"), limiter.calls);
    }

    @Test
    void redisErrorFailsOpenAndIsCounted() {
        limiter.next = () -> Mono.error(new IllegalStateException("Redis недоступен"));
        Result r = get("/api/v1/products", null);
        assertTrue(r.passed(), "при сбое Redis запрос пропускается");
        assertEquals(1.0, meters.counter("dgm.gateway.ratelimit.failopen", "group", "public").count());
    }

    @Test
    void redisThatDoesNotAnswerFailsOpenAfterTimeout() {
        limiter.next = Mono::never;
        Result r = get("/api/v1/products", null);
        assertTrue(r.passed());
        assertEquals(1.0, meters.counter("dgm.gateway.ratelimit.failopen", "group", "public").count());
    }

    @Test
    void failOpenDecisionFromTheLimiterIsCountedToo() {
        limiter.next = () -> Mono.just(RequestLimiter.Decision.failOpen());
        assertTrue(get("/api/v1/products", null).passed());
        assertEquals(1.0, meters.counter("dgm.gateway.ratelimit.failopen", "group", "public").count());
    }

    // ---------------------------------------------------------------------------------------------------- заголовки

    @Test
    void foreignHeadersWithInternalMeaningAreRemoved() {
        Result r = run(MockServerHttpRequest.get("/api/v1/products")
                .header("X-Seller-Id", "00000000-0000-4000-8000-000000000001")
                .header("X-Forwarded-For", "1.2.3.4")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "http")
                .header("Forwarded", "for=1.2.3.4")
                .header("X-Real-IP", "1.2.3.4")
                .build());
        assertTrue(r.passed());
        for (String name : List.of("X-Seller-Id", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "Forwarded", "X-Real-IP")) {
            assertNull(r.forwardedHeader(name), name);
        }
    }

    @Test
    void correlationAndTraceAreKeptWhenValidAndReplacedWhenNot() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        Result kept = run(MockServerHttpRequest.get("/api/v1/products").header("traceparent", traceparent).header("X-Correlation-Id", CORRELATION).build());
        assertEquals(CORRELATION, kept.forwardedHeader("X-Correlation-Id"));
        assertTrue(kept.forwardedHeader("traceparent").startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-"), "трасса сохранена");
        assertNotEquals(traceparent, kept.forwardedHeader("traceparent"), "у шлюза свой отрезок");
        assertEquals(kept.forwardedHeader("traceparent"), kept.header("traceparent"));
        assertEquals(CORRELATION, kept.header("X-Correlation-Id"));

        Result fresh = run(MockServerHttpRequest.get("/api/v1/products").header("traceparent", "garbage").header("X-Correlation-Id", "x").build());
        assertNotEquals("x", fresh.forwardedHeader("X-Correlation-Id"));
        assertTrue(fresh.forwardedHeader("traceparent").matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"));
    }

    @Test
    void everyResponseCarriesCorrelationAndSecurityHeaders() {
        Result r = get("/api/v1/unknown", null);
        assertNotNull(r.header("X-Correlation-Id"));
        assertNotNull(r.header("traceparent"));
        assertEquals("max-age=31536000; includeSubDomains", r.header("Strict-Transport-Security"));
        assertEquals("nosniff", r.header("X-Content-Type-Options"));
        assertEquals("DENY", r.header("X-Frame-Options"));
        assertEquals("no-referrer", r.header("Referrer-Policy"));
        assertEquals(r.header("X-Correlation-Id"), Json.parseObject(r.body()).get("correlationId"), "в теле проблемы тот же идентификатор");
    }

    @Test
    void problemBodyHasStandardMembersAndNoInternals() {
        Result r = get("/api/v1/orders", null);
        var body = Json.parseObject(r.body());
        assertEquals(401, ((Number) body.get("status")).intValue());
        assertEquals("unauthenticated", body.get("code"));
        assertEquals("https://api.marketplace.example/problems/unauthenticated", body.get("type"));
        assertEquals("/api/v1/orders", body.get("instance"));
        assertEquals("no-store", r.header("Cache-Control"));
    }

    // ---------------------------------------------------------------------------------------------------- порт управления

    @Test
    void managementPortIsNotTouched() {
        ServerHttpRequest base = MockServerHttpRequest.get("/actuator/health/readiness").build();
        ServerHttpRequest onManagement = new ServerHttpRequestDecorator(base) {
            @Override
            public InetSocketAddress getLocalAddress() {
                return new InetSocketAddress(8444);
            }
        };
        MockServerWebExchange original = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health/readiness").build());
        ServerWebExchange exchange = original.mutate().request(onManagement).build();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter(8444).filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));
        assertNotNull(forwarded.get(), "запрос порта управления идёт дальше без проверок шлюза");
        assertTrue(limiter.calls.isEmpty());
        assertNull(exchange.getAttribute(EntryFilter.TARGET));
    }

    @Test
    void actuatorPathsAreNotFoundOnTheMainPort() {
        for (String path : List.of("/actuator", "/actuator/prometheus", "/actuator/health/readiness")) {
            Result r = run(filter(8444), MockServerWebExchange.from(MockServerHttpRequest.get(path).build()));
            assertFalse(r.passed(), path);
            assertEquals(404, r.status(), path);
        }
    }

    // ---------------------------------------------------------------------------------------------------- токен из заголовка

    @Test
    void bearerParsing() {
        assertEquals("abc", EntryFilter.bearer("Bearer abc"));
        assertEquals("abc", EntryFilter.bearer("bearer abc"));
        assertEquals("abc", EntryFilter.bearer("BEARER   abc  "));
        assertNull(EntryFilter.bearer(null));
        assertNull(EntryFilter.bearer("Basic abc"));
        assertNull(EntryFilter.bearer("Bearer"));
        assertNull(EntryFilter.bearer("Bearer    "));
    }
}
