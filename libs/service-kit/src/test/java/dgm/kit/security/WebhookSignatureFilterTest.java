package dgm.kit.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dgm.kit.crypto.WebhookSignature;
import dgm.kit.json.Json;
import dgm.kit.route.RouteRule;
import dgm.kit.secret.SecretValue;
import dgm.kit.time.ManualClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Фильтр подписи вебхука: допуск, отказ по подписи, по метке времени, по размеру, не настроенный секрет. */
class WebhookSignatureFilterTest {

    private static final Instant NOW = Instant.parse("2026-10-11T10:00:30Z");
    private static final String TIMESTAMP = "2026-10-11T10:00:00.000Z";
    private static final String SECRET = "payment-secret-value";
    private static final byte[] BODY = "{\"paymentId\":\"p-1\",\"status\":\"succeeded\"}".getBytes(StandardCharsets.UTF_8);
    private static final RouteRule WEBHOOK = new RouteRule("POST", "/api/v1/webhooks/payments", "receivePaymentGatewayNotification", List.of(),
            List.of(), false, List.of(), false, null, "payment_webhook_secret");
    private static final RouteRule ORDINARY = new RouteRule("POST", "/api/v1/orders", "createOrder", List.of("orders.write"), List.of("buyer"),
            false, List.of());

    private final List<String> asked = new ArrayList<>();
    private List<SecretValue> configured = List.of(SecretValue.of(SECRET));
    private final WebhookSignatureFilter filter = new WebhookSignatureFilter(name -> {
        asked.add(name);
        return configured;
    }, new ManualClock(NOW), 1024);

    private static String sign(String secret, String timestamp, byte[] body) {
        return WebhookSignature.sign(secret.getBytes(StandardCharsets.UTF_8), timestamp, body);
    }

    private static MockHttpServletRequest request(RouteRule route, byte[] body, String timestamp, String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest(route.method(), route.path());
        request.setAttribute(JwtFilter.ROUTE_ATTRIBUTE, route);
        request.setContent(body);
        if (timestamp != null) {
            request.addHeader(WebhookSignature.TIMESTAMP_HEADER, timestamp);
        }
        if (signature != null) {
            request.addHeader(WebhookSignature.SIGNATURE_HEADER, signature);
        }
        return request;
    }

    private static String code(MockHttpServletResponse response) throws Exception {
        return String.valueOf(Json.parseObject(response.getContentAsString()).get("code"));
    }

    @Test
    void validSignaturePassesAndControllerReadsTheSameBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(WEBHOOK, BODY, TIMESTAMP, sign(SECRET, TIMESTAMP, BODY)), response, chain);
        assertNotNull(chain.getRequest());
        assertEquals(new String(BODY, StandardCharsets.UTF_8), new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(List.of("payment_webhook_secret"), asked);
        assertEquals(200, response.getStatus());
    }

    @Test
    void previousSecretStillWorksDuringRotation() throws Exception {
        configured = List.of(SecretValue.of("new-secret"), SecretValue.of(SECRET));
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(WEBHOOK, BODY, TIMESTAMP, sign(SECRET, TIMESTAMP, BODY)), new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
    }

    @Test
    void wrongSignatureIsUnauthorizedAndTheChainIsNotCalled() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(WEBHOOK, BODY, TIMESTAMP, sign("other", TIMESTAMP, BODY)), response, chain);
        assertEquals(401, response.getStatus());
        assertEquals("signature-invalid", code(response));
        assertNull(chain.getRequest());
    }

    @Test
    void missingHeadersAreSignatureInvalid() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(WEBHOOK, BODY, null, null), response, chain);
        assertEquals(401, response.getStatus());
        assertEquals("signature-invalid", code(response));
        assertNull(chain.getRequest());
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        byte[] other = "{\"paymentId\":\"p-1\",\"status\":\"failed\"}".getBytes(StandardCharsets.UTF_8);
        filter.doFilter(request(WEBHOOK, other, TIMESTAMP, sign(SECRET, TIMESTAMP, BODY)), response, chain);
        assertEquals("signature-invalid", code(response));
        assertNull(chain.getRequest());
    }

    @Test
    void oldTimestampWithValidSignatureIsExpired() throws Exception {
        String old = "2026-10-11T09:50:00.000Z";
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(WEBHOOK, BODY, old, sign(SECRET, old, BODY)), response, chain);
        assertEquals(401, response.getStatus());
        assertEquals("webhook-expired", code(response));
        assertNull(chain.getRequest());
    }

    @Test
    void secretNotConfiguredIsServerError() throws Exception {
        configured = List.of();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(WEBHOOK, BODY, TIMESTAMP, sign(SECRET, TIMESTAMP, BODY)), response, chain);
        assertEquals(500, response.getStatus());
        assertEquals("internal-error", code(response));
        assertNull(chain.getRequest());
    }

    @Test
    void bodyOverLimitIsRejectedBeforeCheckingTheSignature() throws Exception {
        byte[] big = new byte[2048];
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(WEBHOOK, big, TIMESTAMP, sign(SECRET, TIMESTAMP, big)), response, chain);
        assertEquals(413, response.getStatus());
        assertEquals("payload-too-large", code(response));
        assertNull(chain.getRequest());
    }

    @Test
    void routesWithoutWebhookSecretAreNotTouched() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(ORDINARY, BODY, null, null), new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertEquals(List.of(), asked);
    }

    @Test
    void requestWithoutRouteIsNotTouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
    }
}
