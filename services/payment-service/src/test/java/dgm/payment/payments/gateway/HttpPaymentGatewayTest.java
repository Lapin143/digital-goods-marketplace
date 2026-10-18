package dgm.payment.payments.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dgm.kit.client.PeerClient;
import dgm.kit.json.Json;
import dgm.kit.retry.Sleeper;
import dgm.kit.secret.SecretValue;
import dgm.kit.time.ManualClock;
import dgm.payment.payments.gateway.client.HttpPaymentGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Клиент шлюза на локальном HTTP-сервере JDK (ADR-015): формат запроса, ключ шлюза и ключ идемпотентности, повтор после 503 с тем же
 * ключом, повтор после тайм-аута, отказ 4xx без повтора, предохранитель, ответы не по формату. Секрет не попадает в тексты ошибок.
 */
@Tag("TC-501")
@Tag("TC-508")
class HttpPaymentGatewayTest {

    private static final String SECRET = "gateway-secret-key-0001";
    private static final UUID PAYMENT = UUID.fromString("0199e0a0-0000-7000-8000-000000000401");
    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final Instant END = Instant.parse("2026-10-18T12:12:00.123Z");
    private static final String OK = "{\"paymentId\":\"pay_1\",\"orderId\":\"" + ORDER + "\",\"status\":\"pending\","
            + "\"payUrl\":\"https://localhost:8443/payment/pay/pay_1\",\"expiresAt\":\"2026-10-18T12:12:00.123Z\"}";

    private record Seen(String method, String path, String body, Map<String, String> headers) {
    }

    private record Reply(int status, String body, long delayMillis) {
    }

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<Reply> script = new ConcurrentLinkedDeque<>();
    private ManualClock clock;
    private Sleeper sleeper;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), body, headers));
            Reply reply = script.pollFirst();
            if (reply == null) {
                reply = new Reply(201, OK, 0);
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
        sleeper = clock::advance;
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private PeerClient.Settings settings() {
        URI base = URI.create("http://localhost:" + server.getAddress().getPort());
        return PeerClient.Settings.of("payment-gateway", base, new Random(5)).withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(30));
    }

    private HttpPaymentGateway gateway(PeerClient.Settings settings) {
        PeerClient client = new PeerClient(settings, null, clock, sleeper, new SimpleMeterRegistry());
        return new HttpPaymentGateway(() -> client, () -> SecretValue.of(SECRET));
    }

    private GatewayPayment create(HttpPaymentGateway gateway) {
        return gateway.create(PAYMENT, ORDER, 299800, "RUB", "https://localhost:8443/orders/" + ORDER, END);
    }

    private static String view(String status, long amount, String currency) {
        return "{\"paymentId\":\"pay_1\",\"orderId\":\"" + ORDER + "\",\"amount\":{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"},"
                + "\"status\":\"" + status + "\",\"payUrl\":\"https://localhost:8443/payment/pay/pay_1\",\"createdAt\":\"2026-10-18T10:00:00Z\"}";
    }

    /** Клиент без повторов, как в цепочке вебхука (ключ {@code retry: ""} в настройках узла). */
    private HttpPaymentGateway twoClients(PeerClient.Settings retrying) {
        PeerClient withRetries = new PeerClient(retrying, null, clock, sleeper, new SimpleMeterRegistry());
        PeerClient single = new PeerClient(retrying.withRetry(dgm.kit.retry.RetryPolicy.none()).withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2)), null,
                clock, sleeper, new SimpleMeterRegistry());
        return new HttpPaymentGateway(() -> withRetries, () -> single, () -> SecretValue.of(SECRET));
    }

    @Test
    void thePaymentIsCreatedWithTheKeyOfTheGatewayAndTheIdempotencyKeyOfThePayment() {
        GatewayPayment payment = create(gateway(settings()));

        assertEquals("pay_1", payment.id());
        assertEquals("https://localhost:8443/payment/pay/pay_1", payment.payUrl());
        assertEquals(END, payment.expiresAt());
        assertEquals(1, seen.size());
        Seen call = seen.get(0);
        assertEquals("POST /payment/v1/payments", call.method() + " " + call.path());
        assertEquals("Bearer " + SECRET, call.headers().get("authorization"));
        assertEquals(PAYMENT.toString(), call.headers().get("idempotency-key"));
        assertNull(call.headers().get("x-webhook-timestamp"));
        Map<String, Object> body = Json.parseObject(call.body());
        assertEquals(ORDER.toString(), body.get("orderId"));
        assertEquals(Map.of("amount", 299800, "currency", "RUB"), body.get("amount"));
        assertEquals("https://localhost:8443/orders/" + ORDER, body.get("returnUrl"));
        assertEquals("2026-10-18T12:12:00.123Z", body.get("expiresAt"));
    }

    @Test
    void aTemporaryFailureIsRepeatedWithTheSameIdempotencyKey() {
        script.add(new Reply(503, "{\"error\":{\"code\":\"unavailable\"}}", 0));
        script.add(new Reply(429, "{\"error\":{\"code\":\"rate_limited\"}}", 0));
        GatewayPayment payment = create(gateway(settings()));
        assertEquals("pay_1", payment.id());
        assertEquals(3, seen.size());
        for (Seen call : seen) {
            assertEquals(PAYMENT.toString(), call.headers().get("idempotency-key"), "повтор идёт с тем же ключом, а не с новым");
        }
    }

    @Test
    void aSlowAttemptIsAbandonedAfterTheAttemptTimeoutAndRepeated() {
        script.add(new Reply(201, OK, 900));
        PeerClient.Settings fast = settings().withTimeouts(Duration.ofMillis(300), Duration.ofSeconds(10));
        GatewayPayment payment = create(gateway(fast));
        assertEquals("pay_1", payment.id());
        assertEquals(2, seen.size());
        assertEquals(seen.get(0).headers().get("idempotency-key"), seen.get(1).headers().get("idempotency-key"));
    }

    @Test
    void aRefusalByMeaningIsNotRepeated() {
        script.add(new Reply(400, "{\"error\":{\"code\":\"invalid_request\"}}", 0));
        GatewayRejectedException e = assertThrows(GatewayRejectedException.class, () -> create(gateway(settings())));
        assertEquals(400, e.status());
        assertEquals(1, seen.size());
        assertFalse(String.valueOf(e.getMessage()).contains(SECRET));
    }

    @Test
    void anUnauthorizedAnswerIsARefusalToo() {
        script.add(new Reply(401, "{\"error\":{\"code\":\"unauthorized\"}}", 0));
        GatewayRejectedException e = assertThrows(GatewayRejectedException.class, () -> create(gateway(settings())));
        assertEquals(401, e.status());
        assertEquals(1, seen.size());
    }

    @Test
    void threeFailedAttemptsMakeTheGatewayUnavailableWithoutLeakingTheKey() {
        for (int i = 0; i < 3; i++) {
            script.add(new Reply(503, "{\"error\":{\"code\":\"unavailable\"}}", 0));
        }
        GatewayUnavailableException e = assertThrows(GatewayUnavailableException.class, () -> create(gateway(settings())));
        assertEquals(3, seen.size(), "до трёх попыток");
        assertFalse(String.valueOf(e.getMessage()).contains(SECRET));
        assertFalse(String.valueOf(e.getCause()).contains(SECRET));
        assertTrue(e.getMessage().contains("server_error"), e.getMessage());
    }

    @Test
    void whenTheBreakerIsOpenTheGatewayIsNotCalledAtAll() {
        for (int i = 0; i < 3; i++) {
            script.add(new Reply(503, "{}", 0));
        }
        HttpPaymentGateway gateway = gateway(settings().withBreaker(2, 0.5, Duration.ofSeconds(30)));
        assertThrows(GatewayUnavailableException.class, () -> create(gateway));
        int before = seen.size();
        GatewayUnavailableException e = assertThrows(GatewayUnavailableException.class, () -> create(gateway));
        assertEquals(before, seen.size(), "при открытом предохранителе запрос к шлюзу не уходит");
        assertTrue(e.getMessage().contains("breaker_open"), e.getMessage());
    }

    @Test
    void anAnswerThatIsNotTheExpectedJsonIsATemporaryFailure() {
        script.add(new Reply(201, "это не JSON", 0));
        assertThrows(GatewayUnavailableException.class, () -> create(gateway(settings())));
        script.add(new Reply(201, "{\"paymentId\":\"pay_1\"}", 0));
        GatewayUnavailableException e = assertThrows(GatewayUnavailableException.class, () -> create(gateway(settings())));
        assertTrue(e.getMessage().contains("payUrl"), e.getMessage());
    }

    @Test
    void theSessionEndIsOptionalInTheGatewayAnswer() {
        script.add(new Reply(201, "{\"paymentId\":\"pay_2\",\"payUrl\":\"https://localhost:8443/payment/pay/pay_2\"}", 0));
        GatewayPayment payment = create(gateway(settings()));
        assertEquals("pay_2", payment.id());
        assertNull(payment.expiresAt());
    }

    @Test
    void theStatusIsReadWithAGetRequestAndTheKeyOfTheGateway() {
        script.add(new Reply(200, view("paid", 299800, "RUB"), 0));

        GatewayPaymentState state = gateway(settings()).checkStatus("pay_1");

        assertEquals(new GatewayPaymentState("pay_1", GatewayStatus.PAID, 299800, "RUB"), state);
        assertTrue(state.sameMoney(299800, "RUB"));
        assertFalse(state.sameMoney(299801, "RUB"));
        assertFalse(state.sameMoney(299800, "USD"));
        Seen call = seen.get(0);
        assertEquals("GET /payment/v1/payments/pay_1", call.method() + " " + call.path());
        assertEquals("Bearer " + SECRET, call.headers().get("authorization"));
        assertNull(call.headers().get("idempotency-key"), "у чтения нет ключа идемпотентности");
    }

    @Test
    void everyGatewayStatusMapsToThePlatformModelAndAStrangeOneIsUnknown() {
        String[][] pairs = {{"pending", "PENDING"}, {"paid", "PAID"}, {"declined", "DECLINED"}, {"expired", "EXPIRED"},
                {"refund_pending", "REFUNDING"}, {"refunded", "REFUNDED"}, {"chargeback", "UNKNOWN"}};
        for (String[] pair : pairs) {
            script.add(new Reply(200, view(pair[0], 100, "RUB"), 0));
            assertEquals(GatewayStatus.valueOf(pair[1]), gateway(settings()).lookupStatus("pay_1").status(), pair[0]);
        }
    }

    @Test
    void anIdentifierWithSpecialCharactersIsEncodedInThePath() {
        script.add(new Reply(200, view("pending", 100, "RUB"), 0));
        gateway(settings()).lookupStatus("pay/1 x");
        assertEquals("/payment/v1/payments/pay%2F1+x", seen.get(0).path());
    }

    @Test
    void anUnknownPaymentIsARefusalWithoutRepeats() {
        script.add(new Reply(404, "{\"error\":{\"code\":\"not_found\"}}", 0));
        GatewayRejectedException e = assertThrows(GatewayRejectedException.class, () -> gateway(settings()).lookupStatus("pay_9"));
        assertEquals(404, e.status());
        assertEquals(1, seen.size());
    }

    @Test
    void theLookupRepeatsAfterAFailureButTheSingleAttemptDoesNot() {
        script.add(new Reply(503, "{}", 0));
        script.add(new Reply(200, view("paid", 100, "RUB"), 0));
        HttpPaymentGateway gateway = twoClients(settings());

        assertEquals(GatewayStatus.PAID, gateway.lookupStatus("pay_1").status());
        assertEquals(2, seen.size(), "сверка повторяет запрос");

        seen.clear();
        script.add(new Reply(503, "{}", 0));
        script.add(new Reply(200, view("paid", 100, "RUB"), 0));
        assertThrows(GatewayUnavailableException.class, () -> gateway.checkStatus("pay_1"));
        assertEquals(1, seen.size(), "в цепочке вебхука одна попытка: повтор сделает шлюз, повторив уведомление");
    }

    @Test
    void aSlowStatusAnswerIsAbandonedByTheSingleAttemptAfterTheAttemptTimeout() {
        script.add(new Reply(200, view("paid", 100, "RUB"), 900));
        PeerClient single = new PeerClient(settings().withRetry(dgm.kit.retry.RetryPolicy.none()).withTimeouts(Duration.ofMillis(300), Duration.ofMillis(300)),
                null, clock, sleeper, new SimpleMeterRegistry());
        HttpPaymentGateway gateway = new HttpPaymentGateway(() -> single, () -> single, () -> SecretValue.of(SECRET));

        assertThrows(GatewayUnavailableException.class, () -> gateway.checkStatus("pay_1"));
        assertEquals(1, seen.size());
    }

    @Test
    void aStatusAnswerWithoutTheAmountOrTheStatusIsATemporaryFailure() {
        script.add(new Reply(200, "{\"paymentId\":\"pay_1\",\"status\":\"paid\"}", 0));
        GatewayUnavailableException noAmount = assertThrows(GatewayUnavailableException.class, () -> gateway(settings()).lookupStatus("pay_1"));
        assertTrue(noAmount.getMessage().contains("суммы"), noAmount.getMessage());

        script.add(new Reply(200, "{\"paymentId\":\"pay_1\",\"amount\":{\"amount\":100,\"currency\":\"RUB\"}}", 0));
        GatewayUnavailableException noStatus = assertThrows(GatewayUnavailableException.class, () -> gateway(settings()).lookupStatus("pay_1"));
        assertTrue(noStatus.getMessage().contains("status"), noStatus.getMessage());

        script.add(new Reply(200, "не JSON", 0));
        assertThrows(GatewayUnavailableException.class, () -> gateway(settings()).lookupStatus("pay_1"));
    }
}
