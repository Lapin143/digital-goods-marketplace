package dgm.payment.payments.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Тело {@code OpenPaymentSessionRequest}: допустимое, лишние и пропущенные поля, сумма, валюта, срок, тип содержимого. */
@Tag("TC-501")
class PaymentSessionBodiesTest {

    private static final String ORDER = "0199e0a0-0000-7000-8000-000000000301";

    private static Map<String, Object> valid() {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", 299800);
        money.put("currency", "RUB");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", ORDER);
        body.put("amount", money);
        body.put("sessionExpiresAt", "2026-10-18T12:12:00.123Z");
        return body;
    }

    private static MockHttpServletRequest request(String type, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/payment-sessions");
        request.setContentType(type);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ProblemException e) {
        return (List<Map<String, Object>>) e.extensions().get("errors");
    }

    private static ProblemException invalid(Map<String, Object> body) {
        ProblemException e = assertThrows(ProblemException.class, () -> PaymentSessionBodies.parse(body));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        return e;
    }

    private static boolean has(ProblemException e, String pointer, String code) {
        return errors(e).stream().anyMatch(x -> pointer.equals(x.get("pointer")) && code.equals(x.get("code")));
    }

    @Test
    void aValidBodyIsParsed() {
        PaymentSessionBodies.OpenRequest request = PaymentSessionBodies.parse(valid());
        assertEquals(UUID.fromString(ORDER), request.orderId());
        assertEquals(299800, request.amount());
        assertEquals("RUB", request.currency());
        assertEquals(Instant.parse("2026-10-18T12:12:00.123Z"), request.sessionExpiresAt());
    }

    @Test
    void anExtraFieldAndMissingFieldsAreListedByPointer() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("extra", 1);
        ProblemException e = invalid(body);
        assertTrue(has(e, "/extra", "unknown_value"));
        assertTrue(has(e, "/orderId", "required"));
        assertTrue(has(e, "/amount", "required"));
        assertTrue(has(e, "/sessionExpiresAt", "required"));
    }

    @Test
    void theOrderIdMustBeALowerCaseUuid() {
        Map<String, Object> body = valid();
        body.put("orderId", ORDER.toUpperCase());
        assertTrue(has(invalid(body), "/orderId", "invalid_format"));
        body.put("orderId", 7);
        assertTrue(has(invalid(body), "/orderId", "invalid_format"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAmountIsAWholePositiveNumberOfKopecks() {
        Map<String, Object> body = valid();
        ((Map<String, Object>) body.get("amount")).put("amount", 0);
        assertTrue(has(invalid(body), "/amount/amount", "out_of_range"));
        ((Map<String, Object>) body.get("amount")).put("amount", 12.5);
        assertTrue(has(invalid(body), "/amount/amount", "invalid_format"));
        ((Map<String, Object>) body.get("amount")).put("amount", "100");
        assertTrue(has(invalid(body), "/amount/amount", "invalid_format"));
        ((Map<String, Object>) body.get("amount")).put("amount", 9_007_199_254_740_992L);
        assertTrue(has(invalid(body), "/amount/amount", "out_of_range"));
        body.put("amount", "299800");
        assertTrue(has(invalid(body), "/amount", "invalid_format"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyRoublesAreAccepted() {
        Map<String, Object> body = valid();
        ((Map<String, Object>) body.get("amount")).put("currency", "USD");
        assertTrue(has(invalid(body), "/amount/currency", "invalid_format"));
        ((Map<String, Object>) body.get("amount")).remove("currency");
        assertTrue(has(invalid(body), "/amount/currency", "required"));
        ((Map<String, Object>) body.get("amount")).put("extra", true);
        assertTrue(has(invalid(body), "/amount/extra", "unknown_value"));
    }

    @Test
    void theTimestampIsRfc3339UtcWithMilliseconds() {
        for (String bad : new String[] {"2026-10-18T12:12:00Z", "2026-10-18T15:12:00.123+03:00", "2026-10-18 12:12:00.123Z", "завтра", "2026-13-40T00:00:00.000Z"}) {
            Map<String, Object> body = valid();
            body.put("sessionExpiresAt", bad);
            assertTrue(has(invalid(body), "/sessionExpiresAt", "invalid_format"), bad);
        }
        Map<String, Object> body = valid();
        body.put("sessionExpiresAt", 1760789520);
        assertTrue(has(invalid(body), "/sessionExpiresAt", "invalid_format"));
    }

    @Test
    void notJsonIs400AndAnotherContentTypeIs415() {
        ProblemException notJson = assertThrows(ProblemException.class, () -> PaymentSessionBodies.read(request("application/json", "{не json")));
        assertEquals(ProblemType.BAD_REQUEST, notJson.type());
        ProblemException array = assertThrows(ProblemException.class, () -> PaymentSessionBodies.read(request("application/json", "[1]")));
        assertEquals(ProblemType.BAD_REQUEST, array.type());
        ProblemException text = assertThrows(ProblemException.class, () -> PaymentSessionBodies.read(request("text/plain", Json.write(valid()))));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, text.type());
    }

    @Test
    void aValidRequestIsReadFromTheStream() throws Exception {
        PaymentSessionBodies.OpenRequest request = PaymentSessionBodies.read(request("application/json; charset=utf-8", Json.write(valid())));
        assertEquals(UUID.fromString(ORDER), request.orderId());
    }
}
