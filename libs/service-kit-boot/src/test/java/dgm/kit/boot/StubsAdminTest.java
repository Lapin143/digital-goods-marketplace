package dgm.kit.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dgm.kit.boot.testing.StubsAdmin;
import dgm.kit.boot.testing.StubsAdmin.StubsException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Клиент управления заглушками на поддельном сервере: режим возвращается к умолчаниям, команды идут по нужным путям, ожидания заканчиваются. */
class StubsAdminTest {

    private HttpServer server;
    private StubsAdmin admin;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger emailRequests = new AtomicInteger();
    private volatile int failWith;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String uri = exchange.getRequestURI().toString();
            calls.add(exchange.getRequestMethod() + " " + uri + (body.isEmpty() ? "" : " " + body));
            String answer;
            int status = failWith == 0 ? 200 : failWith;
            if (uri.startsWith("/admin/emails?") || uri.equals("/admin/emails")) {
                answer = emailRequests.incrementAndGet() < 3 ? "{\"items\":[]}" : "{\"items\":[{\"to\":\"a@example.test\",\"messageId\":\"m1\"}]}";
            } else if (uri.startsWith("/admin/payments") && exchange.getRequestMethod().equals("GET")) {
                answer = "{\"items\":[{\"paymentId\":\"p1\",\"orderId\":\"o1\"},{\"paymentId\":\"p2\",\"orderId\":\"o2\"}]}";
            } else {
                answer = "{\"ok\":true}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        admin = StubsAdmin.at("http://127.0.0.1:" + server.getAddress().getPort(), HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void modeIsRestoredAfterBlock() {
        String seen = admin.supplyInMode("payment", Map.of("scenario", "hold"), () -> "тело выполнено");
        assertEquals("тело выполнено", seen);
        assertEquals(List.of("PUT /admin/modes/payment {\"scenario\":\"hold\"}", "PUT /admin/modes/payment {}"), calls);
    }

    @Test
    void modeIsRestoredAfterException() {
        assertThrows(IllegalStateException.class, () -> admin.withEmailMode(Map.of("behavior", "temp_error"), () -> {
            throw new IllegalStateException("сценарий упал");
        }));
        assertEquals("PUT /admin/modes/email {}", calls.get(calls.size() - 1));
    }

    @Test
    void unknownSystemIsRejectedBeforeRequest() {
        assertThrows(IllegalArgumentException.class, () -> admin.setMode("bank", Map.of()));
        assertEquals(List.of(), calls);
    }

    @Test
    void paymentCommandsGoToTheirPaths() {
        admin.confirmPayment("pay 1", Map.of("repeat", 2));
        admin.completeRefund("p2", Map.of());
        admin.resendWebhook("p3", "payment.paid", Map.of("badSignature", true));
        assertEquals(List.of("POST /admin/payments/pay%201/confirm {\"repeat\":2}", "POST /admin/payments/p2/refund-complete {}",
                "POST /admin/payments/p3/webhook {\"badSignature\":true,\"type\":\"payment.paid\"}"), calls);
    }

    @Test
    void paymentOfOrderIsFound() {
        Map<String, Object> payment = admin.awaitPaymentOfOrder("o2", Duration.ofSeconds(2));
        assertEquals("p2", payment.get("paymentId"));
    }

    @Test
    void awaitEmailsPollsUntilEnough() {
        List<Map<String, Object>> found = admin.awaitEmails(1, Duration.ofSeconds(10), "a@example.test", null, "Ваш заказ");
        assertEquals(1, found.size());
        assertEquals(3, emailRequests.get());
        assertTrue(calls.get(0).startsWith("GET /admin/emails?to=a%40example.test&subject="), calls.get(0));
    }

    @Test
    void awaitEmailsGivesUpWithExplanation() {
        emailRequests.set(-1000);
        StubsException e = assertThrows(StubsException.class, () -> admin.awaitEmails(1, Duration.ofMillis(100), "x@example.test", null, null));
        assertTrue(e.getMessage().contains("найдено 0 из 1"), e.getMessage());
    }

    @Test
    void errorCodeBecomesException() {
        failWith = 500;
        StubsException e = assertThrows(StubsException.class, () -> admin.reset());
        assertTrue(e.getMessage().contains("код 500"), e.getMessage());
    }
}
