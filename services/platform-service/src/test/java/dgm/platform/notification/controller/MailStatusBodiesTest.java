package dgm.platform.notification.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.notification.domain.MailStatus;
import dgm.platform.notification.domain.MailStatusNotice;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Разбор тела {@code MailProviderNotification} (TC-718): схема строгая (лишнее поле и неверный тип это 400, не JSON это 400, не тот тип
 * содержимого это 415), статус перечисление открытое и незнакомый статус допустим, идентификатор сообщения чужого вида это не ошибка разбора.
 */
@Tag("TC-718")
class MailStatusBodiesTest {

    private static final String MESSAGE = "0199e0a0-0000-7000-8000-000000000101";

    private static Map<String, Object> valid() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", MESSAGE);
        body.put("status", "delivered");
        body.put("occurredAt", "2026-10-26T12:00:00.123Z");
        return body;
    }

    private static void refuses(Consumer<Map<String, Object>> change) {
        Map<String, Object> body = valid();
        change.accept(body);
        ProblemException e = assertThrows(ProblemException.class, () -> MailStatusBodies.parse(body));
        assertEquals(ProblemType.BAD_REQUEST, e.type());
    }

    private static MockHttpServletRequest request(String contentType, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/webhooks/email-provider");
        if (contentType != null) {
            request.setContentType(contentType);
        }
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void aDeliveredNoticeIsParsed() {
        MailStatusNotice notice = MailStatusBodies.parse(valid());

        assertEquals(MESSAGE, notice.messageId());
        assertEquals(UUID.fromString(MESSAGE), notice.notificationId().orElseThrow());
        assertEquals(MailStatus.DELIVERED, notice.status());
        assertEquals(Instant.parse("2026-10-26T12:00:00.123Z"), notice.occurredAt());
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

        assertEquals("address_rejected", MailStatusBodies.parse(rejected).reason());
        assertEquals("other", MailStatusBodies.parse(other).reason());
        assertEquals(MailStatus.FAILED, MailStatusBodies.parse(rejected).status());
    }

    @Test
    void anAcceptedNoticeAndAnUnknownStatusAreParsedTheEnumerationIsOpen() {
        Map<String, Object> accepted = valid();
        accepted.put("status", "accepted");
        Map<String, Object> unknown = valid();
        unknown.put("status", "opened_by_recipient");
        Map<String, Object> upper = valid();
        upper.put("status", "DELIVERED");

        assertEquals(MailStatus.ACCEPTED, MailStatusBodies.parse(accepted).status());
        assertEquals(MailStatus.OTHER, MailStatusBodies.parse(unknown).status());
        assertEquals(MailStatus.DELIVERED, MailStatusBodies.parse(upper).status());
    }

    @Test
    void aMessageIdentifierOfAnotherShapeIsNotANotificationOfThePlatform() {
        Map<String, Object> body = valid();
        body.put("messageId", "msg_44a1b9");
        Map<String, Object> upper = valid();
        upper.put("messageId", MESSAGE.toUpperCase(Locale.ROOT));

        assertTrue(MailStatusBodies.parse(body).notificationId().isEmpty());
        assertTrue(MailStatusBodies.parse(upper).notificationId().isEmpty(), "идентификатор уведомления строчными буквами, как в письме провайдеру");
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
        ProblemException e = assertThrows(ProblemException.class, () -> MailStatusBodies.parse(Map.of("messageId", "secret-value-1", "status", "failed",
                "occurredAt", "2026-10-26T12:00:00Z", "reason", "secret-value-2")));
        assertEquals(false, String.valueOf(e.getMessage()).contains("secret-value"), "значения полей в тексте ошибки не нужны");
    }

    @Test
    void theBodyIsReadFromTheRequestAfterTheContentTypeIsChecked() throws IOException {
        MailStatusNotice notice = MailStatusBodies.read(request("application/json",
                "{\"messageId\": \"" + MESSAGE + "\", \"status\": \"delivered\", \"occurredAt\": \"2026-10-26T12:00:00Z\"}"));
        assertEquals(MailStatus.DELIVERED, notice.status());

        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> MailStatusBodies.read(request("application/json", "не JSON"))).type());
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> MailStatusBodies.read(request("application/json", "[1, 2]"))).type());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> MailStatusBodies.read(request("text/plain", "{}"))).type());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> MailStatusBodies.read(request(null, "{}"))).type());
    }

    @Test
    void aBodyOverSixtyFourKilobytesIsRefused() {
        String body = "{\"messageId\": \"" + "m".repeat(70 * 1024) + "\"}";

        ProblemException e = assertThrows(ProblemException.class, () -> MailStatusBodies.read(request("application/json", body)));

        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, e.type());
    }
}
