package dgm.kit.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.route.RouteRule;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.CallerFilter;
import dgm.kit.security.JwtFilter;
import dgm.kit.time.ManualClock;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionOperations;

/** Фильтр ключа идемпотентности: заголовок, область ключа, воспроизведение ответа, что не запоминается. */
class IdempotencyFilterTest {

    private static final String REQUEST_UUID = "3f2b8c6e-1a4d-4e0b-9d7a-5c1e2f3a4b5c";
    private static final RouteRule ORDER = new RouteRule("POST", "/api/v1/orders", "createOrder", List.of("orders.write"), List.of("buyer"), false,
            List.of(), true, null, null);
    private static final RouteRule READ = new RouteRule("GET", "/api/v1/orders", "listOrders", List.of("orders.read"), List.of("buyer"), false,
            List.of());
    private static final RouteRule INTERNAL = new RouteRule("POST", "/internal/v1/otp/issue", "issueOtpCode", List.of(), List.of(), false,
            List.of("keycloak"), true, null, null);

    private InMemoryIdempotencyStore store;
    private IdempotencyFilter filter;
    private AtomicInteger runs;
    private final List<String> seenBodies = new ArrayList<>();
    private int status;
    private String responseBody;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyStore();
        ManualClock clock = new ManualClock(Instant.parse("2026-10-11T10:00:00Z"));
        IdempotencyGuard guard = new IdempotencyGuard(store, TransactionOperations.withoutTransaction(),
                new IdempotencyGuard.Settings(Duration.ZERO, Duration.ofMillis(10), Duration.ofSeconds(60)), clock);
        filter = new IdempotencyFilter(rule -> guard, 1024);
        runs = new AtomicInteger();
        status = 201;
        responseBody = "{\"id\":\"42\"}";
    }

    /** Цепочка после фильтра: читает тело и отвечает заданным кодом. */
    private FilterChain chain() {
        return (request, response) -> {
            runs.incrementAndGet();
            seenBodies.add(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(status);
            if (status < 300) {
                http.setHeader("Location", "/api/v1/orders/42");
                http.setHeader("ETag", "\"1\"");
            }
            http.setContentType(status < 300 ? "application/json" : "application/problem+json");
            http.getWriter().write(responseBody);
        };
    }

    private MockHttpServletResponse send(RouteRule route, String key, String body, String user, String caller) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(route.method(), route.path());
        request.setAttribute(JwtFilter.ROUTE_ATTRIBUTE, route);
        if (key != null) {
            request.addHeader("Idempotency-Key", key);
        }
        if (user != null) {
            request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser(user, Set.of("orders.write"), Set.of("buyer"), List.of("pwd"), true));
        }
        if (caller != null) {
            request.setAttribute(CallerFilter.CALLER_ATTRIBUTE, caller);
        }
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain());
        return response;
    }

    @Test
    void routesWithoutKeyPassThrough() throws Exception {
        MockHttpServletResponse response = send(READ, null, "", "u1", null);
        assertEquals(201, response.getStatus());
        assertEquals(1, runs.get());
        assertEquals(0, store.size());
    }

    @Test
    void missingHeaderIsBadRequest() throws Exception {
        MockHttpServletResponse response = send(ORDER, null, "{}", "u1", null);
        assertEquals(400, response.getStatus());
        assertEquals("idempotency-key-required", Json.parseObject(response.getContentAsString()).get("code"));
        assertEquals(0, runs.get());
    }

    @Test
    void headerThatIsNotUuidV4IsBadRequest() throws Exception {
        MockHttpServletResponse response = send(ORDER, "12345", "{}", "u1", null);
        assertEquals(400, response.getStatus());
        assertEquals("bad-request", Json.parseObject(response.getContentAsString()).get("code"));
        assertEquals(0, runs.get());
    }

    @Test
    void firstRequestRunsAndClientGetsTheResponse() throws Exception {
        MockHttpServletResponse response = send(ORDER, REQUEST_UUID, "{\"item\":1}", "u1", null);
        assertEquals(201, response.getStatus());
        assertEquals("{\"id\":\"42\"}", response.getContentAsString());
        assertEquals("/api/v1/orders/42", response.getHeader("Location"));
        assertNull(response.getHeader("Idempotency-Replayed"));
        assertEquals(List.of("{\"item\":1}"), seenBodies);
        assertEquals(1, store.size());
    }

    @Test
    void repeatGetsTheSameResponseWithoutRunningTheChain() throws Exception {
        MockHttpServletResponse first = send(ORDER, REQUEST_UUID, "{\"item\":1}", "u1", null);
        MockHttpServletResponse second = send(ORDER, REQUEST_UUID, "{\"item\":1}", "u1", null);
        assertEquals(1, runs.get());
        assertEquals(first.getStatus(), second.getStatus());
        assertEquals(Json.parseObject(first.getContentAsString()), Json.parseObject(second.getContentAsString()));
        assertEquals("true", second.getHeader("Idempotency-Replayed"));
        assertEquals("/api/v1/orders/42", second.getHeader("Location"));
        assertEquals("\"1\"", second.getHeader("ETag"));
        assertTrue(second.getContentType().startsWith("application/json"));
    }

    @Test
    void sameKeyWithAnotherBodyIsUnprocessable() throws Exception {
        send(ORDER, REQUEST_UUID, "{\"item\":1}", "u1", null);
        MockHttpServletResponse second = send(ORDER, REQUEST_UUID, "{\"item\":2}", "u1", null);
        assertEquals(422, second.getStatus());
        assertEquals("idempotency-key-reuse", Json.parseObject(second.getContentAsString()).get("code"));
        assertEquals(1, runs.get());
    }

    @Test
    void keysOfDifferentUsersDoNotCollide() throws Exception {
        send(ORDER, REQUEST_UUID, "{}", "u1", null);
        MockHttpServletResponse other = send(ORDER, REQUEST_UUID, "{}", "u2", null);
        assertEquals(201, other.getStatus());
        assertNull(other.getHeader("Idempotency-Replayed"));
        assertEquals(2, runs.get());
    }

    @Test
    void internalCallScopeIsTheCallerName() throws Exception {
        send(INTERNAL, REQUEST_UUID, "{\"phone\":\"x\"}", null, "keycloak");
        MockHttpServletResponse repeat = send(INTERNAL, REQUEST_UUID, "{\"phone\":\"x\"}", null, "keycloak");
        assertEquals("true", repeat.getHeader("Idempotency-Replayed"));
        assertEquals(1, runs.get());
    }

    @Test
    void errorResponseIsNotRemembered() throws Exception {
        status = 409;
        responseBody = "{\"code\":\"insufficient-stock\"}";
        MockHttpServletResponse failed = send(ORDER, REQUEST_UUID, "{}", "u1", null);
        assertEquals(409, failed.getStatus());
        assertEquals("{\"code\":\"insufficient-stock\"}", failed.getContentAsString());
        assertEquals(0, store.size());

        status = 201;
        responseBody = "{\"id\":\"43\"}";
        MockHttpServletResponse retried = send(ORDER, REQUEST_UUID, "{}", "u1", null);
        assertEquals(201, retried.getStatus());
        assertNull(retried.getHeader("Idempotency-Replayed"));
        assertEquals(2, runs.get());
    }

    @Test
    void unfinishedFirstRequestGivesConflictWithRetryAfter() throws Exception {
        // В хранилище запись «выполняется»: так видит первый запрос параллельный повтор
        InMemoryIdempotencyStore busy = new InMemoryIdempotencyStore();
        busy.claim(new IdempotencyRequest("u1", java.util.UUID.fromString(REQUEST_UUID), "POST /api/v1/orders",
                IdempotencyRequest.sha256("{}".getBytes(StandardCharsets.UTF_8))));
        IdempotencyGuard guard = new IdempotencyGuard(busy, TransactionOperations.withoutTransaction(),
                new IdempotencyGuard.Settings(Duration.ZERO, Duration.ofMillis(10), Duration.ofSeconds(60)),
                new ManualClock(Instant.parse("2026-10-11T10:00:00Z")));
        filter = new IdempotencyFilter(rule -> guard, 1024);
        MockHttpServletResponse repeat = send(ORDER, REQUEST_UUID, "{}", "u1", null);
        assertEquals(409, repeat.getStatus());
        assertEquals("request-in-progress", Json.parseObject(repeat.getContentAsString()).get("code"));
        assertEquals("1", repeat.getHeader("Retry-After"));
    }

    @Test
    void bodyLargerThanLimitIsRejectedBeforeAnyWork() throws Exception {
        MockHttpServletResponse response = send(ORDER, REQUEST_UUID, "x".repeat(2000), "u1", null);
        assertEquals(413, response.getStatus());
        assertEquals("payload-too-large", Json.parseObject(response.getContentAsString()).get("code"));
        assertEquals(0, runs.get());
        assertEquals(0, store.size());
    }

    @Test
    void routeWithKeyButNoPrincipalIsAConfigurationError() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> send(ORDER, REQUEST_UUID, "{}", null, null));
    }
}
