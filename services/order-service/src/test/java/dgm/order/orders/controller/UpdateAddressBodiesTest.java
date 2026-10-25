package dgm.order.orders.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.order.orders.controller.UpdateAddressBodies.UpdateRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Разбор тела {@code UpdateDeliveryAddressRequest} (OP-72): два обязательных поля, лишние поля (additionalProperties: false), адрес приводится к нормальному
 * виду, типы тела. Значение адреса в ошибки не попадает.
 */
@Tag("TC-529")
class UpdateAddressBodiesTest {

    private static final String TICKET = "0199e0a0-0000-7000-8000-000000000501";

    private static Map<String, Object> body(Object email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", TICKET);
        body.put("email", email);
        return body;
    }

    private static ProblemException refused(Map<String, Object> body) {
        ProblemException e = assertThrows(ProblemException.class, () -> UpdateAddressBodies.parse(body));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        return e;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ProblemException e) {
        return (List<Map<String, Object>>) e.extensions().get("errors");
    }

    private static List<String> pointers(Map<String, Object> body) {
        return errors(refused(body)).stream().map(error -> (String) error.get("pointer")).toList();
    }

    private static List<String> codes(Map<String, Object> body) {
        return errors(refused(body)).stream().map(error -> (String) error.get("code")).toList();
    }

    private static MockHttpServletRequest request(String contentType, String content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/orders/0199e0a0-0000-7000-8000-000000000301/update-delivery-address");
        if (contentType != null) {
            request.setContentType(contentType);
        }
        request.setContent(content.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void aValidRequestIsParsed() {
        UpdateRequest request = UpdateAddressBodies.parse(body("buyer@mail.example"));

        assertEquals(UUID.fromString(TICKET), request.ticketId());
        assertEquals("buyer@mail.example", request.email());
    }

    @Test
    void theAddressIsTrimmedAndLowercasedLikeTheAddressOfTheAccount() {
        assertEquals("buyer@mail.example", UpdateAddressBodies.parse(body("  Buyer@Mail.Example ")).email());
    }

    @Test
    void anAddressThatIsNotAnAddressIsRefusedWithoutEchoingIt() {
        for (Object bad : new Object[] {"", "ab", "no-at-sign", "@mail.example", "name@", "name@nodot", "two@@mail.example", "sp ace@mail.example", 5, true}) {
            ProblemException e = refused(body(bad));
            assertEquals(List.of("/email"), pointers(body(bad)), String.valueOf(bad));
            assertEquals(List.of("invalid_format"), codes(body(bad)), String.valueOf(bad));
            if (bad instanceof String text && text.length() > 2) {
                assertFalse(String.valueOf(e.getMessage()).contains(text), "значение адреса в сообщении не повторяется: " + text);
                assertFalse(errors(e).toString().contains(text), "значение адреса в ошибках не повторяется: " + text);
            }
        }
    }

    @Test
    void theAddressIsNotLongerThan254Characters() {
        String longest = "a".repeat(254 - "@mail.example".length()) + "@mail.example";
        assertEquals(254, longest.length());
        assertEquals(longest, UpdateAddressBodies.parse(body(longest)).email());
        assertEquals(List.of("too_long"), codes(body("a" + longest)));
    }

    @Test
    void missingFieldsAreReportedWithTheRequiredCode() {
        assertEquals(List.of("/ticketId", "/email"), pointers(new LinkedHashMap<>()));
        assertEquals(List.of("required", "required"), codes(new LinkedHashMap<>()));
        Map<String, Object> noEmail = new LinkedHashMap<>();
        noEmail.put("ticketId", TICKET);
        assertEquals(List.of("/email"), pointers(noEmail));
        assertEquals(List.of("/email"), pointers(body(null)));
    }

    @Test
    void anExtraFieldIsRefused() {
        Map<String, Object> body = body("buyer@mail.example");
        body.put("orderId", TICKET);
        body.put("status", "issued");

        assertEquals(List.of("/orderId", "/status"), pointers(body));
        assertEquals(List.of("unknown_value", "unknown_value"), codes(body));
    }

    @Test
    void theTicketIdMustBeALowercaseUuidString() {
        for (Object bad : new Object[] {TICKET.toUpperCase(), 5, "not-a-uuid", ""}) {
            Map<String, Object> body = body("buyer@mail.example");
            body.put("ticketId", bad);
            assertEquals(List.of("/ticketId"), pointers(body), String.valueOf(bad));
        }
    }

    @Test
    void everyErrorIsReportedAtOnce() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("extra", 1);
        body.put("ticketId", "x");
        body.put("email", "y");
        assertEquals(List.of("/extra", "/ticketId", "/email"), pointers(body));
    }

    @Test
    void aJsonRequestIsReadFromTheBody() throws IOException {
        String content = "{\"ticketId\":\"" + TICKET + "\",\"email\":\"buyer@mail.example\"}";

        assertEquals(new UpdateRequest(UUID.fromString(TICKET), "buyer@mail.example"), UpdateAddressBodies.read(request("application/json", content)));
        assertEquals(new UpdateRequest(UUID.fromString(TICKET), "buyer@mail.example"),
                UpdateAddressBodies.read(request("application/json; charset=UTF-8", content)));
    }

    @Test
    void anotherContentTypeIs415() {
        for (String type : new String[] {"text/plain", "application/x-www-form-urlencoded", null}) {
            ProblemException e = assertThrows(ProblemException.class, () -> UpdateAddressBodies.read(request(type, "{}")));
            assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, e.type(), String.valueOf(type));
        }
    }

    @Test
    void aBodyThatIsNotAJsonObjectIs400() {
        for (String content : new String[] {"", "not json", "[1, 2]", "\"text\"", "{\"ticketId\":"}) {
            ProblemException e = assertThrows(ProblemException.class, () -> UpdateAddressBodies.read(request("application/json", content)));
            assertEquals(ProblemType.BAD_REQUEST, e.type(), content);
        }
    }

    @Test
    void aBodyLargerThanFourKibIs413() {
        String large = "{\"ticketId\":\"" + TICKET + "\",\"email\":\"buyer@mail.example\",\"pad\":\"" + "x".repeat(5000) + "\"}";

        ProblemException e = assertThrows(ProblemException.class, () -> UpdateAddressBodies.read(request("application/json", large)));
        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, e.type());
    }

    @Test
    void theTextOfTheRequestHidesTheAddress() {
        assertFalse(UpdateAddressBodies.parse(body("canary@mail.example")).toString().contains("canary"));
    }
}
