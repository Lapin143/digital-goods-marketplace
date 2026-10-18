package dgm.payment.payments.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.payment.payments.webhook.GatewayNotification.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Разбор тела {@code PaymentGatewayNotification}: схема строгая (лишнее поле и неверный тип это 400), неизвестный тип уведомления допустим. */
@Tag("TC-506")
class GatewayNotificationBodiesTest {

    private static final String ORDER = "0199e0a0-0000-7000-8000-000000000301";

    private static Map<String, Object> valid() {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", 299800);
        money.put("currency", "RUB");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", "pay_5d2e91");
        data.put("orderId", ORDER);
        data.put("amount", money);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", "evt_8f3a1c");
        body.put("type", "payment.paid");
        body.put("occurredAt", "2026-10-03T12:00:00.123Z");
        body.put("data", data);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> body) {
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> money(Map<String, Object> body) {
        return (Map<String, Object>) data(body).get("amount");
    }

    private static void refuses(Consumer<Map<String, Object>> change) {
        Map<String, Object> body = valid();
        change.accept(body);
        ProblemException e = assertThrows(ProblemException.class, () -> GatewayNotificationBodies.parse(body));
        assertEquals(ProblemType.BAD_REQUEST, e.type());
    }

    @Test
    void aValidNotificationIsParsed() {
        GatewayNotification n = GatewayNotificationBodies.parse(valid());
        assertEquals("evt_8f3a1c", n.eventId());
        assertEquals("payment.paid", n.type());
        assertEquals(Kind.PAID, n.kind());
        assertEquals(Instant.parse("2026-10-03T12:00:00.123Z"), n.occurredAt());
        assertEquals("pay_5d2e91", n.paymentId());
        assertEquals(UUID.fromString(ORDER), n.orderId());
        assertEquals(299800, n.amount());
        assertEquals("RUB", n.currency());
        assertNull(n.refundId());
        assertNull(n.failureReason());
    }

    @Test
    void theOptionalFieldsOfDeclineAndRefundAreRead() {
        Map<String, Object> declined = valid();
        declined.put("type", "payment.declined");
        data(declined).put("failureReason", "insufficient_funds");
        GatewayNotification d = GatewayNotificationBodies.parse(declined);
        assertEquals(Kind.DECLINED, d.kind());
        assertEquals("insufficient_funds", d.failureReason());

        Map<String, Object> refund = valid();
        refund.put("type", "refund.completed");
        data(refund).put("refundId", "ref_1");
        GatewayNotification r = GatewayNotificationBodies.parse(refund);
        assertEquals(Kind.REFUND_COMPLETED, r.kind());
        assertEquals("ref_1", r.refundId());
    }

    @Test
    void anUnknownTypeIsAcceptedAndMapsToUnknown() {
        Map<String, Object> body = valid();
        body.put("type", "payment.something-new");
        assertEquals(Kind.UNKNOWN, GatewayNotificationBodies.parse(body).kind());
        body.put("type", "payment.session-expired");
        assertEquals(Kind.SESSION_EXPIRED, GatewayNotificationBodies.parse(body).kind());
    }

    @Test
    void extraFieldsAtAnyLevelAreRefused() {
        refuses(b -> b.put("extra", 1));
        refuses(b -> data(b).put("extra", 1));
        refuses(b -> money(b).put("extra", 1));
    }

    @Test
    void missingOrMalformedFieldsAreRefused() {
        refuses(b -> b.remove("eventId"));
        refuses(b -> b.put("eventId", ""));
        refuses(b -> b.put("eventId", "x".repeat(129)));
        refuses(b -> b.put("type", 5));
        refuses(b -> b.put("occurredAt", "вчера"));
        refuses(b -> b.remove("occurredAt"));
        refuses(b -> b.put("data", "строка"));
        refuses(b -> b.remove("data"));
        refuses(b -> data(b).remove("paymentId"));
        refuses(b -> data(b).put("orderId", "не-идентификатор"));
        refuses(b -> data(b).put("orderId", ORDER.toUpperCase()));
        refuses(b -> data(b).remove("amount"));
        refuses(b -> data(b).put("amount", "много"));
        refuses(b -> money(b).put("amount", 12.5));
        refuses(b -> money(b).put("amount", -1));
        refuses(b -> money(b).put("amount", 9_007_199_254_740_992L));
        refuses(b -> money(b).put("currency", "rub"));
        refuses(b -> money(b).remove("currency"));
        refuses(b -> data(b).put("failureReason", "бывает"));
        refuses(b -> data(b).put("refundId", ""));
    }

    @Test
    void theBodyIsReadFromTheRequestAndTheContentTypeIsChecked() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/webhooks/payment-gateway");
        request.setContentType("application/json");
        request.setContent(Json.write(valid()).getBytes(StandardCharsets.UTF_8));
        assertEquals("evt_8f3a1c", GatewayNotificationBodies.read(request).eventId());

        MockHttpServletRequest notJson = new MockHttpServletRequest("POST", "/api/v1/webhooks/payment-gateway");
        notJson.setContentType("text/plain");
        notJson.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> GatewayNotificationBodies.read(notJson)).type());

        MockHttpServletRequest broken = new MockHttpServletRequest("POST", "/api/v1/webhooks/payment-gateway");
        broken.setContentType("application/json");
        broken.setContent("{не json".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> GatewayNotificationBodies.read(broken)).type());

        MockHttpServletRequest missingType = new MockHttpServletRequest("POST", "/api/v1/webhooks/payment-gateway");
        missingType.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> GatewayNotificationBodies.read(missingType)).type());
    }
}
