package dgm.payment.payments.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.kit.time.ManualClock;
import dgm.payment.payments.FakePayments;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.domain.RefundPayment;
import dgm.payment.payments.domain.RefundSchedule;
import dgm.payment.payments.service.RefundOutcomes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Операции {@code listManualRefunds} и {@code completeManualRefund} по схемам {@code ManualRefundPage} и
 * {@code CompleteManualRefundRequest} (OP-57, US-5.8): страницы и курсор, ответ без лишних и персональных полей, проверка тела и пути.
 */
@Tag("TC-525")
@Tag("TC-526")
@Tag("TC-527")
class ManualRefundControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00.123Z");
    private static final String ADMIN = "0199e0a0-0000-4000-8000-0000000000a1";
    private static final String BASE = "/api/v1/staff/manual-refunds";
    private static final long AMOUNT = 299800;

    private FakePayments fake;
    private ManualRefundController controller;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        ManualClock clock = new ManualClock(NOW);
        RefundOutcomes outcomes = new RefundOutcomes(fake.refunds, fake.events, fake.transaction, RefundSchedule.parse("0s,30s,2m,5m,10m", 0, new Random(1)),
                clock, new SimpleMeterRegistry());
        controller = new ManualRefundController(new ManualRefundService(fake.refunds, outcomes, fake.transaction, clock));
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 400 + n));
    }

    private void queued(int n) {
        fake.refunds.put(new RefundPayment(id(n), UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 300 + n)), "pay_" + n, AMOUNT, "RUB",
                Payment.CONFIRMED, "seller_api_timeout", null, true, NOW.plus(Duration.ofMinutes(n)), null));
    }

    private static MockHttpServletRequest get(String... parameters) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", BASE);
        authenticate(request);
        for (int i = 0; i < parameters.length; i += 2) {
            request.setParameter(parameters[i], parameters[i + 1]);
        }
        return request;
    }

    private static MockHttpServletRequest post(UUID payment, String contentType, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", BASE + "/" + payment + "/complete");
        authenticate(request);
        request.setRemoteAddr("203.0.113.7");
        if (contentType != null) {
            request.setContentType(contentType);
        }
        if (body != null) {
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    private static void authenticate(MockHttpServletRequest request) {
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser(ADMIN, Set.of("staff.admin"), Set.of("admin"), List.of("pwd", "otp"), true));
    }

    private static Map<String, Object> view(ResponseEntity<String> response) {
        return Json.parseObject(response.getBody());
    }

    @Test
    void theQueueIsAnsweredWith200AndTheDocumentedFieldsOnly() {
        queued(1);

        ResponseEntity<String> response = controller.queue(get());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> page = view(response);
        assertEquals(Set.of("items", "page"), page.keySet());
        List<?> items = (List<?>) page.get("items");
        assertEquals(1, items.size());
        Map<?, ?> row = (Map<?, ?>) items.get(0);
        assertEquals(Set.of("paymentId", "orderId", "amount", "refundReason", "attempts", "escalatedAt"), row.keySet(), "ни внешнего идентификатора, ни ключей");
        assertEquals(id(1).toString(), row.get("paymentId"));
        assertEquals("seller_api_timeout", row.get("refundReason"));
        Map<?, ?> money = (Map<?, ?>) row.get("amount");
        assertEquals(AMOUNT, ((Number) money.get("amount")).longValue());
        assertEquals("RUB", money.get("currency"));
        assertEquals(0, ((Number) row.get("attempts")).intValue());
        Map<?, ?> info = (Map<?, ?>) page.get("page");
        assertEquals(20, ((Number) info.get("limit")).intValue());
        assertNull(info.get("nextCursor"));
    }

    @Test
    void theCursorOfOnePageOpensTheNext() {
        queued(1);
        queued(2);
        queued(3);

        Map<String, Object> first = view(controller.queue(get("limit", "2")));
        String cursor = (String) ((Map<?, ?>) first.get("page")).get("nextCursor");
        assertTrue(cursor != null && !cursor.isBlank());
        assertEquals(2, ((List<?>) first.get("items")).size());

        Map<String, Object> second = view(controller.queue(get("limit", "2", "cursor", cursor)));
        List<?> items = (List<?>) second.get("items");
        assertEquals(1, items.size());
        assertEquals(id(3).toString(), ((Map<?, ?>) items.get(0)).get("paymentId"));
        assertNull(((Map<?, ?>) second.get("page")).get("nextCursor"));
    }

    @Test
    void wrongQueryParametersAreAValidationErrorWithTheirNames() {
        for (String[] parameters : new String[][] {{"limit", "0"}, {"limit", "101"}, {"limit", "abc"}, {"cursor", "not-a-cursor"}, {"status", "all"}}) {
            ProblemException e = assertThrows(ProblemException.class, () -> controller.queue(get(parameters)), String.join("=", parameters));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type());
            assertTrue(e.extensions().containsKey("errors"));
        }
    }

    @Test
    void theMarkIsAnsweredWith200AndTheRefundedStatus() throws Exception {
        queued(1);

        ResponseEntity<String> response = controller.complete(id(1).toString(), post(id(1), "application/json", "{\"externalRefundId\":\"manual-77\"}"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = view(response);
        assertEquals(Set.of("paymentId", "orderId", "paymentStatus", "refundedAt"), body.keySet());
        assertEquals(id(1).toString(), body.get("paymentId"));
        assertEquals("refunded", body.get("paymentStatus"));
        assertEquals("2026-10-18T10:00:00.123Z", body.get("refundedAt"));
        assertEquals("manual-77", fake.refunds.payment(id(1)).gatewayRefundId());
        Map<String, Object> audit = fake.transaction.events.get(1).data();
        assertEquals(ADMIN, audit.get("actorId"));
        assertEquals("203.0.113.7", audit.get("actorIp"));
    }

    @Test
    void theBodyIsOptional() throws Exception {
        queued(1);
        queued(2);
        queued(3);

        assertEquals(200, controller.complete(id(1).toString(), post(id(1), null, null)).getStatusCode().value());
        assertEquals(200, controller.complete(id(2).toString(), post(id(2), "application/json", "")).getStatusCode().value());
        assertEquals(200, controller.complete(id(3).toString(), post(id(3), "application/json", "{}")).getStatusCode().value());
        assertNull(fake.refunds.payment(id(3)).gatewayRefundId());
    }

    @Test
    void aSecondMarkOfTheSamePaymentIs409() throws Exception {
        queued(1);
        controller.complete(id(1).toString(), post(id(1), null, null));

        ProblemException e = assertThrows(ProblemException.class, () -> controller.complete(id(1).toString(), post(id(1), null, null)));
        assertEquals(ProblemType.REFUND_NOT_ALLOWED, e.type());
    }

    @Test
    void aBodyThatIsNotTheDocumentedObjectIsRefusedBeforeAnythingChanges() {
        queued(1);
        UUID payment = id(1);
        String path = payment.toString();

        ProblemException notJson = assertThrows(ProblemException.class, () -> controller.complete(path, post(payment, "application/json", "[1]")));
        assertEquals(ProblemType.BAD_REQUEST, notJson.type());
        ProblemException broken = assertThrows(ProblemException.class, () -> controller.complete(path, post(payment, "application/json", "{")));
        assertEquals(ProblemType.BAD_REQUEST, broken.type());
        ProblemException media = assertThrows(ProblemException.class, () -> controller.complete(path, post(payment, "text/plain", "{}")));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, media.type());
        for (String body : new String[] {"{\"externalRefundId\":5}", "{\"externalRefundId\":\" \"}", "{\"externalRefundId\":\"" + "x".repeat(129) + "\"}",
                "{\"extra\":1}", "{\"externalRefundId\":\"ok\",\"extra\":true}"}) {
            ProblemException e = assertThrows(ProblemException.class, () -> controller.complete(path, post(payment, "application/json", body)), body);
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), body);
            assertTrue(e.extensions().containsKey("errors"), body);
        }

        assertEquals(Payment.CONFIRMED, fake.refunds.payment(payment).status());
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Test
    void theLongestAllowedIdentifierIsAccepted() throws Exception {
        queued(1);
        controller.complete(id(1).toString(), post(id(1), "application/json", "{\"externalRefundId\":\"" + "x".repeat(128) + "\"}"));
        assertEquals("x".repeat(128), fake.refunds.payment(id(1)).gatewayRefundId());
    }

    @Test
    void thePaymentInThePathMustBeALowercaseUuid() {
        for (String bad : new String[] {"123", id(1).toString().toUpperCase(java.util.Locale.ROOT), id(1) + "x", "../etc"}) {
            ProblemException e = assertThrows(ProblemException.class, () -> controller.complete(bad, post(id(1), null, null)), bad);
            assertEquals(ProblemType.BAD_REQUEST, e.type(), bad);
        }
    }

    @Test
    void anUnknownPaymentIs404() {
        ProblemException e = assertThrows(ProblemException.class, () -> controller.complete(id(1).toString(), post(id(1), null, null)));
        assertEquals(ProblemType.NOT_FOUND, e.type());
    }
}
