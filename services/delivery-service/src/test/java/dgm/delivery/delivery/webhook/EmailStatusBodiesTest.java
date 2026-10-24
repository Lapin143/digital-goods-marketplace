package dgm.delivery.delivery.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.delivery.delivery.domain.ProviderStatus;
import dgm.delivery.delivery.domain.StatusNotice;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
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

/**
 * Разбор тела {@code EmailStatusNotification} (TC-607): схема строгая (лишнее поле и неверный тип это 400, не JSON это 400, не тот тип содержимого
 * это 415), статус перечисление открытое и незнакомый статус допустим, идентификатор сообщения чужого вида это не ошибка разбора.
 */
@Tag("TC-607")
class EmailStatusBodiesTest {

    private static final String MESSAGE = "0199e0a0-0000-7000-8000-000000000101";

    private static Map<String, Object> valid() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", MESSAGE);
        body.put("status", "delivered");
        body.put("occurredAt", "2026-10-03T12:00:00.123Z");
        return body;
    }

    private static void refuses(Consumer<Map<String, Object>> change) {
        Map<String, Object> body = valid();
        change.accept(body);
        ProblemException e = assertThrows(ProblemException.class, () -> EmailStatusBodies.parse(body));
        assertEquals(ProblemType.BAD_REQUEST, e.type());
    }

    @Test
    void aDeliveredNoticeIsParsed() {
        StatusNotice notice = EmailStatusBodies.parse(valid());

        assertEquals(MESSAGE, notice.messageId());
        assertEquals(UUID.fromString(MESSAGE), notice.deliveryId().orElseThrow());
        assertEquals(ProviderStatus.DELIVERED, notice.status());
        assertEquals(Instant.parse("2026-10-03T12:00:00.123Z"), notice.occurredAt());
        assertNull(notice.reason());
    }

    @Test
    void aFailedNoticeCarriesTheReasonFromTheSchema() {
        Map<String, Object> rejected = valid();
        rejected.put("status", "failed");
        rejected.put("reason", "address_rejected");
        Map<String, Object> other = valid();
        other.put("status", "failed");
        other.put("reason", "other");

        assertEquals("address_rejected", EmailStatusBodies.parse(rejected).reason());
        assertEquals("other", EmailStatusBodies.parse(other).reason());
        assertEquals(ProviderStatus.FAILED, EmailStatusBodies.parse(rejected).status());
    }

    @Test
    void anAcceptedNoticeAndAnUnknownStatusAreParsedTheEnumerationIsOpen() {
        Map<String, Object> accepted = valid();
        accepted.put("status", "accepted");
        Map<String, Object> unknown = valid();
        unknown.put("status", "opened_by_recipient");
        Map<String, Object> upper = valid();
        upper.put("status", "DELIVERED");

        assertEquals(ProviderStatus.ACCEPTED, EmailStatusBodies.parse(accepted).status());
        assertEquals(ProviderStatus.OTHER, EmailStatusBodies.parse(unknown).status());
        assertEquals(ProviderStatus.DELIVERED, EmailStatusBodies.parse(upper).status());
    }

    @Test
    void aMessageIdentifierOfAnotherShapeIsNotTheDeliveryOfThePlatform() {
        Map<String, Object> body = valid();
        body.put("messageId", "msg_91c3d7");
        Map<String, Object> upper = valid();
        upper.put("messageId", MESSAGE.toUpperCase(java.util.Locale.ROOT));

        assertEquals(true, EmailStatusBodies.parse(body).deliveryId().isEmpty());
        assertEquals(true, EmailStatusBodies.parse(upper).deliveryId().isEmpty(), "идентификатор выдачи строчными буквами, как в письме");
    }

    @Test
    void aBodyThatBreaksTheSchemaIsRefusedWithoutEchoingItsValues() {
        refuses(body -> body.put("extra", 1));
        refuses(body -> body.remove("messageId"));
        refuses(body -> body.put("messageId", ""));
        refuses(body -> body.put("messageId", "m".repeat(129)));
        refuses(body -> body.put("messageId", 7));
        refuses(body -> body.remove("status"));
        refuses(body -> body.put("status", ""));
        refuses(body -> body.put("status", 5));
        refuses(body -> body.remove("occurredAt"));
        refuses(body -> body.put("occurredAt", "вчера"));
        refuses(body -> body.put("occurredAt", 1760875200));
        refuses(body -> body.put("reason", "bounced"));
        refuses(body -> body.put("reason", 3));
        ProblemException e = assertThrows(ProblemException.class, () -> EmailStatusBodies.parse(Map.of("messageId", "secret-value-1", "status", "failed",
                "occurredAt", "2026-10-03T12:00:00Z", "reason", "secret-value-2")));
        assertEquals(false, String.valueOf(e.getMessage()).contains("secret-value"), "значения полей в тексте ошибки не нужны");
    }

    @Test
    void theBodyIsReadFromTheRequestAfterTheContentTypeIsChecked() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/webhooks/email-provider-keys");
        request.setContentType("application/json");
        request.setContent(("{\"messageId\": \"" + MESSAGE + "\", \"status\": \"delivered\", \"occurredAt\": \"2026-10-03T12:00:00Z\"}")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(ProviderStatus.DELIVERED, EmailStatusBodies.read(request).status());

        MockHttpServletRequest notJson = new MockHttpServletRequest("POST", "/api/v1/webhooks/email-provider-keys");
        notJson.setContentType("application/json");
        notJson.setContent("не JSON".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> EmailStatusBodies.read(notJson)).type());

        MockHttpServletRequest array = new MockHttpServletRequest("POST", "/api/v1/webhooks/email-provider-keys");
        array.setContentType("application/json");
        array.setContent("[1, 2]".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> EmailStatusBodies.read(array)).type());

        MockHttpServletRequest text = new MockHttpServletRequest("POST", "/api/v1/webhooks/email-provider-keys");
        text.setContentType("text/plain");
        text.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> EmailStatusBodies.read(text)).type());

        MockHttpServletRequest missingType = new MockHttpServletRequest("POST", "/api/v1/webhooks/email-provider-keys");
        missingType.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> EmailStatusBodies.read(missingType)).type());
    }
}
