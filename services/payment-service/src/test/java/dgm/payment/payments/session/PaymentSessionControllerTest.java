package dgm.payment.payments.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Ответ {@code openPaymentSession}: код 201, схема {@code PaymentSessionOpened}, без внешнего идентификатора платежа и без кэша. */
class PaymentSessionControllerTest {

    private static final Instant START = Instant.parse("2026-10-18T12:00:00Z");
    private static final SessionSettings SETTINGS = new SessionSettings(Duration.ofMinutes(15), Duration.ofMinutes(12), Duration.ofMinutes(1),
            "https://localhost:8443");
    private static final String ORDER = "0199e0a0-0000-7000-8000-000000000301";

    private FakePayments fake;
    private PaymentSessionController controller;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        ManualClock clock = new ManualClock(START);
        controller = new PaymentSessionController(
                new PaymentSessionService(fake.payments, fake.transaction, fake.gateway, SETTINGS, clock, new SimpleMeterRegistry()));
    }

    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/payment-sessions");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static String body(String sessionExpiresAt) {
        return "{\"orderId\":\"" + ORDER + "\",\"amount\":{\"amount\":299800,\"currency\":\"RUB\"},\"sessionExpiresAt\":\"" + sessionExpiresAt + "\"}";
    }

    @Test
    void theSessionIsAnsweredWith201AndTheDocumentedFieldsOnly() throws Exception {
        ResponseEntity<String> response = controller.open(request(body("2026-10-18T12:12:00.000Z")));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertNull(response.getHeaders().getLocation(), "в контракте внутренней операции Location нет");
        Map<String, Object> view = Json.parseObject(response.getBody());
        assertEquals(Set.of("paymentId", "orderId", "status", "sessionUrl", "sessionExpiresAt"), view.keySet());
        assertEquals(ORDER, view.get("orderId"));
        assertEquals("created", view.get("status"));
        assertEquals("https://localhost:8443/payment/pay/pay_1", view.get("sessionUrl"));
        assertEquals("2026-10-18T12:12:00.000Z", view.get("sessionExpiresAt"));
        assertFalse(response.getBody().contains("pay_1\"") && response.getBody().contains("gatewayPaymentId"), "внешний идентификатор наружу не отдаётся");
        assertFalse(view.containsKey("gatewayPaymentId"));
    }

    @Test
    void anUnavailableGatewayIs503WithRetryAfter() {
        fake.gateway.failNext = 3;
        ProblemException e = assertThrows(ProblemException.class, () -> controller.open(request(body("2026-10-18T12:12:00.000Z"))));
        assertEquals(ProblemType.PAYMENT_SESSION_UNAVAILABLE, e.type());
        assertEquals(503, e.type().status());
        assertTrue(e.headers().containsKey("Retry-After"));
    }

    @Test
    void anInvalidBodyNeverReachesTheGateway() {
        assertThrows(ProblemException.class, () -> controller.open(request("{}")));
        assertTrue(fake.gateway.calls.isEmpty());
        assertTrue(fake.payments.byOrder.isEmpty());
    }
}
