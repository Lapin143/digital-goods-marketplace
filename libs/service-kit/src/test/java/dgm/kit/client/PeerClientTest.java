package dgm.kit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dgm.kit.retry.RetryPolicy;
import dgm.kit.retry.Sleeper;
import dgm.kit.time.ManualClock;
import dgm.kit.trace.TraceContext;
import dgm.kit.trace.TraceContexts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Клиент вызова соседа на локальном HTTP-сервере JDK: заголовки трассировки и идемпотентности, повторы только идемпотентных запросов,
 * паузы с разбросом, тайм-аут попытки, общий срок, предохранитель, метрики, тексты ошибок.
 */
class PeerClientTest {

    private static final String PEER = "inventory-service";
    private static final String KEY = "0199e0a0-0000-4000-8000-0000000000aa";

    private record Seen(String method, String path, String body, Map<String, String> headers) {
    }

    private record Reply(int status, String body, long delayMillis) {
    }

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<Reply> script = new ConcurrentLinkedDeque<>();
    private final List<Duration> pauses = new CopyOnWriteArrayList<>();
    private ManualClock clock;
    private MeterRegistry metrics;
    private Sleeper sleeper;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
            String uri = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            seen.add(new Seen(exchange.getRequestMethod(), uri, body, headers));
            Reply reply = script.pollFirst();
            if (reply == null) {
                reply = new Reply(200, "{}", 0);
            }
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Peer-Marker", "m1");
            try {
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (IOException e) {
                // Клиент уже ушёл по тайм-ауту
            } finally {
                exchange.close();
            }
        });
        server.start();
        clock = new ManualClock(Instant.parse("2026-10-18T10:00:00Z"));
        metrics = new SimpleMeterRegistry();
        sleeper = d -> {
            pauses.add(d);
            clock.advance(d);
        };
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI base() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    private PeerClient client() {
        return client(PeerClient.Settings.of(PEER, base(), new Random(11)).withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(30)));
    }

    private PeerClient client(PeerClient.Settings settings) {
        return new PeerClient(settings, null, clock, sleeper, metrics);
    }

    private double count(String outcome) {
        return metrics.counter("dgm_client_requests_total", "peer", PEER, "outcome", outcome).count();
    }

    // ---- Заголовки ----

    @Test
    void getReturnsStatusBodyAndHeadersOfAnswer() {
        script.add(new Reply(200, "{\"ok\":true,\"n\":7}", 0));
        PeerResponse response = client().call(PeerRequest.get("/internal/v1/reservations/r1?x=1"));
        assertEquals(200, response.status());
        assertEquals(Boolean.TRUE, response.json().get("ok"));
        assertEquals(7, ((Number) response.json().get("n")).intValue());
        assertEquals("m1", response.header("X-Peer-Marker"));
        assertEquals("GET /internal/v1/reservations/r1?x=1", seen.get(0).method() + " " + seen.get(0).path());
        assertEquals(1.0, count("ok"), 0);
    }

    @Test
    void traceAndCorrelationHeadersContinueTheCurrentTrace() {
        TraceContext incoming = TraceContext.incoming("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                "0199e0a0-0000-4000-8000-000000000001");
        TraceContexts.Scope scope = TraceContexts.open(incoming);
        try {
            client().call(PeerRequest.get("/x"));
        } finally {
            scope.close();
        }
        Map<String, String> h = seen.get(0).headers();
        assertEquals("0199e0a0-0000-4000-8000-000000000001", h.get("x-correlation-id"));
        assertTrue(h.get("traceparent").startsWith("00-" + incoming.traceId() + "-"), h.get("traceparent"));
        assertTrue(h.get("traceparent").endsWith("-01"));
        assertFalse(h.get("traceparent").contains(incoming.spanId()), "у вызова должен быть собственный отрезок");
        assertNull(h.get("idempotency-key"));
    }

    @Test
    void withoutCurrentContextFreshTraceIsStarted() {
        client().call(PeerRequest.get("/x"));
        Map<String, String> h = seen.get(0).headers();
        assertTrue(h.get("traceparent").matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"), h.get("traceparent"));
        assertTrue(h.get("x-correlation-id").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test
    void postSendsBodyContentTypeAndIdempotencyKey() {
        client().call(PeerRequest.post("/internal/v1/reservations", "{\"a\":1}", KEY));
        Seen s = seen.get(0);
        assertEquals("POST", s.method());
        assertEquals("{\"a\":1}", s.body());
        assertEquals("application/json", s.headers().get("content-type"));
        assertEquals(KEY, s.headers().get("idempotency-key"));
    }

    @Test
    void callerCannotOverrideServiceHeaders() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Idempotency-Key", "evil");
        extra.put("X-Correlation-Id", "evil");
        extra.put("traceparent", "evil");
        extra.put("X-Custom", "v");
        client().call(new PeerRequest("POST", "/x", "{}", KEY, extra));
        Map<String, String> h = seen.get(0).headers();
        assertEquals(KEY, h.get("idempotency-key"));
        assertTrue(h.get("traceparent").startsWith("00-"));
        assertFalse(h.get("x-correlation-id").equals("evil"));
        assertEquals("v", h.get("x-custom"));
    }

    // ---- Повторы ----

    @Test
    void postWithKeyIsRetriedWithSameKeyBodyAndCorrelation() {
        script.add(new Reply(503, "", 0));
        script.add(new Reply(502, "", 0));
        script.add(new Reply(200, "{\"done\":true}", 0));
        PeerResponse response = client().call(PeerRequest.post("/internal/v1/reservations", "{\"a\":1}", KEY));
        assertEquals(Boolean.TRUE, response.json().get("done"));
        assertEquals(3, seen.size());
        for (Seen s : seen) {
            assertEquals(KEY, s.headers().get("idempotency-key"));
            assertEquals("{\"a\":1}", s.body());
            assertEquals(seen.get(0).headers().get("x-correlation-id"), s.headers().get("x-correlation-id"));
        }
        assertEquals(2, pauses.size());
        assertTrue(pauses.get(0).toMillis() >= 240 && pauses.get(0).toMillis() <= 360, "первая пауза " + pauses.get(0));
        assertTrue(pauses.get(1).toMillis() >= 560 && pauses.get(1).toMillis() <= 840, "вторая пауза " + pauses.get(1));
        assertEquals(2.0, count("server_error"), 0);
        assertEquals(1.0, count("ok"), 0);
    }

    @Test
    void postWithoutKeyIsNotRetried() {
        script.add(new Reply(503, "", 0));
        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class,
                () -> client().call(PeerRequest.post("/x", "{}", null)));
        assertEquals("server_error", e.reason());
        assertEquals(503, e.lastStatus());
        assertEquals(1, seen.size());
        assertTrue(pauses.isEmpty());
    }

    @Test
    void getIsRetriedOnServerErrorsAndTooManyRequestsButNotOnOtherClientErrors() {
        script.add(new Reply(429, "", 0));
        script.add(new Reply(500, "", 0));
        script.add(new Reply(200, "{}", 0));
        client().call(PeerRequest.get("/x"));
        assertEquals(3, seen.size());

        seen.clear();
        script.add(new Reply(408, "", 0));
        DependencyRejectedException e = assertThrows(DependencyRejectedException.class, () -> client().call(PeerRequest.get("/x")));
        assertEquals(408, e.status());
        assertEquals(1, seen.size(), "ADR-015: повторяются сеть, 5xx и 429, остальные 4xx нет");
    }

    @Test
    void afterAllAttemptsTheLastFailureIsReported() {
        script.add(new Reply(500, "", 0));
        script.add(new Reply(502, "", 0));
        script.add(new Reply(504, "", 0));
        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client().call(PeerRequest.get("/x")));
        assertEquals(3, seen.size());
        assertEquals("server_error", e.reason());
        assertEquals(504, e.lastStatus());
        assertEquals(PEER, e.peer());
    }

    @Test
    void rejectionIsNotRetriedAndCarriesProblemCode() {
        script.add(new Reply(409, "{\"type\":\"https://dgm.example/problems/insufficient-stock\",\"status\":409}", 0));
        DependencyRejectedException e = assertThrows(DependencyRejectedException.class,
                () -> client().call(PeerRequest.post("/x", "{}", KEY)));
        assertEquals(409, e.status());
        assertEquals("insufficient-stock", e.problemCode());
        assertEquals(409, ((Number) e.json().get("status")).intValue());
        assertEquals(1, seen.size());
        assertTrue(pauses.isEmpty());
        assertEquals(1.0, count("rejected"), 0);
    }

    @Test
    void rejectionWithoutProblemBodyHasEmptyCode() {
        script.add(new Reply(404, "", 0));
        DependencyRejectedException e = assertThrows(DependencyRejectedException.class, () -> client().call(PeerRequest.get("/x")));
        assertEquals(404, e.status());
        assertEquals("", e.problemCode());
        assertTrue(e.json().isEmpty());
        assertEquals("", e.body());
    }

    @Test
    void redirectIsNotFollowedAndCountsAsRejection() {
        script.add(new Reply(302, "", 0));
        DependencyRejectedException e = assertThrows(DependencyRejectedException.class, () -> client().call(PeerRequest.get("/x")));
        assertEquals(302, e.status());
        assertEquals(1, seen.size());
    }

    // ---- Тайм-ауты и сеть ----

    @Test
    void everyAttemptIsLimitedByItsOwnTimeoutAndRetried() {
        for (int i = 0; i < 3; i++) {
            script.add(new Reply(200, "{}", 700));
        }
        PeerClient client = client(PeerClient.Settings.of(PEER, base(), new Random(11))
                .withTimeouts(Duration.ofMillis(150), Duration.ofSeconds(30)));
        long started = System.nanoTime();
        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.call(PeerRequest.get("/x")));
        long tookMillis = (System.nanoTime() - started) / 1_000_000;
        assertEquals("timeout", e.reason());
        assertEquals(3, seen.size());
        assertTrue(tookMillis < 2_000, "три попытки по 150 мс не должны ждать ответа 700 мс: " + tookMillis);
        assertEquals(3.0, count("timeout"), 0);
    }

    @Test
    void connectionRefusedIsRetriedAndReportedAsIoError() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        PeerClient client = client(PeerClient.Settings.of(PEER, URI.create("http://localhost:" + closedPort), new Random(11))
                .withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(30)));
        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.call(PeerRequest.get("/x")));
        assertEquals("io_error", e.reason());
        assertEquals(0, e.lastStatus());
        assertEquals(2, pauses.size());
        assertEquals(3.0, count("io_error"), 0);
        assertFalse(e.getMessage().contains(Integer.toString(closedPort)), "в тексте ошибки не должно быть адреса");
    }

    @Test
    void retriesStopWhenNextPauseWouldPassTheTotalDeadline() {
        script.add(new Reply(503, "", 0));
        PeerClient client = client(PeerClient.Settings.of(PEER, base(), new Random(11))
                .withRetry(RetryPolicy.parse("10s,10s", 0, new Random(1)))
                .withTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(5)));
        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.call(PeerRequest.get("/x")));
        assertEquals("deadline", e.reason());
        assertEquals(503, e.lastStatus());
        assertEquals(1, seen.size());
        assertTrue(pauses.isEmpty(), "ждать паузу, после которой срок уже вышел, нельзя");
    }

    @Test
    void interruptedPauseStopsTheCallAndKeepsTheInterruptFlag() {
        script.add(new Reply(503, "", 0));
        sleeper = d -> {
            throw new InterruptedException("test");
        };
        try {
            DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client().call(PeerRequest.get("/x")));
            assertEquals("interrupted", e.reason());
            assertEquals(1, seen.size());
            assertTrue(Thread.interrupted(), "флаг прерывания потока должен сохраниться");
        } finally {
            Thread.interrupted();
        }
    }

    // ---- Предохранитель и метрики ----

    @Test
    void breakerOpensShortCircuitsAndClosesAfterSuccessfulProbe() {
        for (int i = 0; i < 4; i++) {
            script.add(new Reply(503, "", 0));
        }
        PeerClient client = client(PeerClient.Settings.of(PEER, base(), new Random(11))
                .withRetry(RetryPolicy.none()).withBreaker(4, 0.5, Duration.ofSeconds(30)));
        for (int i = 0; i < 4; i++) {
            DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.call(PeerRequest.get("/x")));
            assertEquals("server_error", e.reason());
        }
        assertEquals(CircuitBreaker.State.OPEN, client.breakerState());
        assertEquals(2.0, metrics.get("dgm_client_breaker_state").tag("peer", PEER).gauge().value(), 0);

        DependencyUnavailableException open = assertThrows(DependencyUnavailableException.class, () -> client.call(PeerRequest.get("/x")));
        assertEquals("breaker_open", open.reason());
        assertEquals(4, seen.size(), "при открытом предохранителе запрос к соседу не уходит");
        assertEquals(1.0, count("breaker_open"), 0);

        clock.advance(Duration.ofSeconds(30));
        assertEquals(1.0, metrics.get("dgm_client_breaker_state").tag("peer", PEER).gauge().value(), 0);
        client.call(PeerRequest.get("/x"));
        assertEquals(5, seen.size());
        assertEquals(CircuitBreaker.State.CLOSED, client.breakerState());
        assertEquals(0.0, metrics.get("dgm_client_breaker_state").tag("peer", PEER).gauge().value(), 0);
    }

    @Test
    void breakerOpeningInTheMiddleOfRetriesStopsFurtherAttempts() {
        for (int i = 0; i < 3; i++) {
            script.add(new Reply(503, "", 0));
        }
        PeerClient client = client(PeerClient.Settings.of(PEER, base(), new Random(11)).withBreaker(2, 0.5, Duration.ofSeconds(30)));
        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.call(PeerRequest.get("/x")));
        assertEquals("breaker_open", e.reason());
        assertEquals(503, e.lastStatus());
        assertEquals(2, seen.size(), "третья попытка не уходит: предохранитель открылся после второй");
    }

    @Test
    void rejectionsDoNotOpenTheBreaker() {
        PeerClient client = client(PeerClient.Settings.of(PEER, base(), new Random(11))
                .withRetry(RetryPolicy.none()).withBreaker(4, 0.5, Duration.ofSeconds(30)));
        for (int i = 0; i < 8; i++) {
            script.add(new Reply(404, "", 0));
        }
        for (int i = 0; i < 8; i++) {
            assertThrows(DependencyRejectedException.class, () -> client.call(PeerRequest.get("/x")));
        }
        assertEquals(CircuitBreaker.State.CLOSED, client.breakerState());
        assertEquals(8, seen.size());
    }

    // ---- Сообщения об ошибках и настройки ----

    @Test
    void errorMessagesDoNotContainBodyOrAddress() {
        script.add(new Reply(500, "{\"secret\":\"super-secret-key-value\"}", 0));
        script.add(new Reply(500, "{\"secret\":\"super-secret-key-value\"}", 0));
        script.add(new Reply(500, "{\"secret\":\"super-secret-key-value\"}", 0));
        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client().call(PeerRequest.get("/x")));
        assertFalse(e.getMessage().contains("super-secret-key-value"));
        assertFalse(e.getMessage().contains("localhost"));

        script.add(new Reply(422, "{\"secret\":\"super-secret-key-value\"}", 0));
        DependencyRejectedException r = assertThrows(DependencyRejectedException.class, () -> client().call(PeerRequest.get("/x")));
        assertFalse(r.getMessage().contains("super-secret-key-value"));
    }

    @Test
    void unavailableTurnsIntoProblemWithRetryAfter() {
        DependencyUnavailableException e = new DependencyUnavailableException(PEER, "timeout", 0, null);
        assertEquals("503", Integer.toString(e.toProblem(5).type().status()));
        assertEquals("5", e.toProblem(5).headers().get("Retry-After"));
    }

    @Test
    void defaultSettingsFollowAdr015() {
        PeerClient.Settings s = PeerClient.Settings.of(PEER, base(), new Random(1));
        assertEquals(Duration.ofSeconds(2), s.attemptTimeout());
        assertEquals(3, s.retry().maxAttempts());
        assertEquals(Duration.ofMillis(7_200), s.totalDeadline());
        assertEquals(10, s.breakerWindow());
        assertEquals(0.5, s.breakerRatio(), 0);
        assertEquals(Duration.ofSeconds(30), s.breakerOpenFor());
    }

    @Test
    void wrongSettingsAreRejected() {
        PeerClient.Settings s = PeerClient.Settings.of(PEER, base(), new Random(1));
        assertThrows(IllegalArgumentException.class, () -> s.withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> s.withTimeouts(Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> s.withBreaker(0, 0.5, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> s.withBreaker(10, 0.5, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> PeerClient.Settings.of(" ", base(), new Random(1)));
    }

    // ---- Запрос ----

    @Test
    void onlyIdempotentRequestsMayBeRepeated() {
        assertTrue(PeerRequest.get("/x").idempotent());
        assertTrue(new PeerRequest("PUT", "/x", "{}", null, Map.of()).idempotent());
        assertTrue(new PeerRequest("DELETE", "/x", null, null, Map.of()).idempotent());
        assertFalse(PeerRequest.post("/x", "{}", null).idempotent());
        assertTrue(PeerRequest.post("/x", "{}", KEY).idempotent());
        assertFalse(new PeerRequest("PATCH", "/x", "{}", null, Map.of()).idempotent());
        assertThrows(IllegalArgumentException.class, () -> PeerRequest.get("x"));
        assertThrows(IllegalArgumentException.class, () -> PeerRequest.get(null));
    }
}
