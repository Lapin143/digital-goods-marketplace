package dgm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.client.PeerClient;
import dgm.kit.json.Json;
import dgm.kit.retry.Sleeper;
import dgm.kit.trace.TraceContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Вызов {@code checkApiKey} клиентом каркаса: способ, адрес, тело, заголовки трассы, разбор ответа (ADR-027, п. 3). */
class CatalogKeyCheckTest {

    private static final String DIGEST = "12e10c2d53ed9875ac670787e0e03dd6278959d6f50af238aea1fd2c77cd8023";
    private static final String SELLER = "0199e0a0-0000-7000-8000-000000000101";
    private static final String KEY_ID = "0199e0a0-0000-7000-8000-000000000901";

    private record Seen(String method, String uri, String contentType, String traceparent, String correlation, String body) {
    }

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile String answer = "{\"valid\":true,\"apiKeyId\":\"" + KEY_ID + "\",\"sellerId\":\"" + SELLER
            + "\",\"sellerStatus\":\"approved\"}";
    private HttpServer server;

    private CatalogKeyCheck start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("traceparent"), exchange.getRequestHeaders().getFirst("X-Correlation-Id"), body));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        PeerClient.Settings settings = PeerClient.Settings.of("catalog-service", base).withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2));
        return new CatalogKeyCheck(new PeerClient(settings, null, Clock.systemUTC(), Sleeper.SYSTEM, new SimpleMeterRegistry()));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Tag("TC-955")
    void theCallIsAPostToTheInternalPathWithTheHashInTheBodyAndTheTraceOfTheRequest() throws Exception {
        TraceContext trace = TraceContext.fresh();
        ApiKeyVerifier.CatalogAnswer result = start().check(DIGEST, trace).block(Duration.ofSeconds(5));

        assertEquals(new ApiKeyVerifier.CatalogAnswer(true, KEY_ID, SELLER, "approved"), result);
        assertEquals(1, seen.size());
        Seen call = seen.get(0);
        assertEquals("POST", call.method());
        assertEquals("/internal/v1/api-keys/check", call.uri(), "хеш не в адресе: адрес попадает в журнал доступа");
        assertTrue(call.contentType().startsWith("application/json"));
        assertEquals(Map.of("keyHash", DIGEST), Json.parseObject(call.body()));
        assertEquals(trace.correlationId(), call.correlation(), "сквозной идентификатор запроса идёт и в проверку ключа");
        assertTrue(call.traceparent().contains(trace.traceId()));
    }

    @Test
    @Tag("TC-955")
    void anUnknownKeyAnswerHasNoIdentifiers() throws Exception {
        answer = "{\"valid\":false,\"apiKeyId\":null,\"sellerId\":null,\"sellerStatus\":null}";
        ApiKeyVerifier.CatalogAnswer result = start().check(DIGEST, TraceContext.fresh()).block(Duration.ofSeconds(5));
        assertFalse(result.valid());
        assertNull(result.apiKeyId());
        assertNull(result.sellerId());
        assertNull(result.sellerStatus());
    }

    @Test
    @Tag("TC-955")
    void aServerErrorIsAnOutageAndIsNotRetriedBecauseThePostHasNoIdempotencyKey() throws Exception {
        status.set(503);
        CatalogKeyCheck check = start();
        assertThrows(DependencyUnavailableException.class, () -> check.check(DIGEST, TraceContext.fresh()).block(Duration.ofSeconds(5)));
        assertEquals(1, seen.size(), "повтор POST без ключа идемпотентности опасен, а память шлюза и так сглаживает всплески");
    }

    @Test
    @Tag("TC-955")
    void aRefusalOfTheCatalogToTheGatewayIsAlsoAnErrorNotAnAnswerAboutTheKey() throws Exception {
        status.set(403);
        answer = "{\"code\":\"forbidden\"}";
        CatalogKeyCheck check = start();
        assertThrows(DependencyRejectedException.class, () -> check.check(DIGEST, TraceContext.fresh()).block(Duration.ofSeconds(5)));
    }

    @Test
    @Tag("TC-955")
    void anAnswerOfAnotherShapeIsAnError() {
        assertThrows(IllegalStateException.class, () -> CatalogKeyCheck.answer(Map.of("sellerId", SELLER)));
        assertThrows(IllegalStateException.class, () -> CatalogKeyCheck.answer(Map.of("valid", "yes")));
        ApiKeyVerifier.CatalogAnswer partial = CatalogKeyCheck.answer(Map.of("valid", true, "apiKeyId", 7));
        assertTrue(partial.valid());
        assertNull(partial.apiKeyId(), "идентификатор не строка: считается отсутствующим, дальше решает проверка полноты");
    }
}
