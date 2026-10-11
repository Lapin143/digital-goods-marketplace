package dgm.platform.identity.otp.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.identity.otp.testing.OtpWorld;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Приём статуса SMS по схеме SmsProviderNotification: ответ 200 без тела, повтор не считается дважды, сбой Redis приём не отменяет. */
class SmsStatusControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");

    private OtpWorld world;
    private SmsStatusController controller;

    @BeforeEach
    void setUp() {
        world = new OtpWorld(T0);
        controller = new SmsStatusController(world.statuses);
    }

    private ResponseEntity<Void> receive(String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/webhooks/sms-provider");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return controller.receive(request);
    }

    private static String notification(String id, String status) {
        return "{\"messageId\":\"" + id + "\",\"status\":\"" + status + "\",\"occurredAt\":\"2026-10-11T12:00:01.000Z\"}";
    }

    private double count(String status) {
        var counter = world.meters.find("sms_delivery_total").tag("status", status).counter();
        return counter == null ? 0 : counter.count();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static ProblemException failure(ThrowingCall call) {
        return assertThrows(ProblemException.class, call::run);
    }

    private static List<String> errors(ProblemException e) {
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) e.extensions().get("errors")) {
            Map<?, ?> error = (Map<?, ?>) item;
            result.add(error.get("pointer") + ":" + error.get("code"));
        }
        return result;
    }

    @Test
    void acceptedStatusAnswers200WithoutBodyAndIsCounted() throws Exception {
        ResponseEntity<Void> response = receive(notification("sms-1", "delivered"));

        assertEquals(200, response.getStatusCode().value());
        assertNull(response.getBody());
        assertEquals(1, count("delivered"));
    }

    @Test
    void repeatedNotificationIsAcceptedButCountedOnce() throws Exception {
        receive(notification("sms-1", "delivered"));
        ResponseEntity<Void> repeat = receive(notification("sms-1", "delivered"));
        receive(notification("sms-1", "failed"));
        receive(notification("sms-2", "delivered"));

        assertEquals(200, repeat.getStatusCode().value());
        assertEquals(2, count("delivered"));
        assertEquals(1, count("failed"), "другой статус того же сообщения это другое событие");
    }

    @Test
    void markExpiresAfterADayAndTheStatusIsCountedAgain() throws Exception {
        receive(notification("sms-1", "delivered"));
        world.clock.advance(Duration.ofHours(25));

        receive(notification("sms-1", "delivered"));

        assertEquals(2, count("delivered"));
    }

    @Test
    void redisOutageDoesNotStopTheAcknowledgement() throws Exception {
        world.store.down(true);

        ResponseEntity<Void> response = receive(notification("sms-1", "failed"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, count("failed"));
    }

    @Test
    void invalidNotificationsAreRejectedWithFieldErrors() {
        assertEquals(List.of("/status:unknown_value"), errors(failure(() -> receive(notification("sms-1", "read")))));
        assertEquals(ProblemType.BAD_REQUEST, failure(() -> receive(notification("sms-1", "read"))).type(), "контракт вебхука: 400, не 422");
        assertEquals(List.of("/messageId:required", "/status:required", "/occurredAt:required"), errors(failure(() -> receive("{}"))));
        assertEquals(List.of("/occurredAt:invalid_format"),
                errors(failure(() -> receive("{\"messageId\":\"1\",\"status\":\"failed\",\"occurredAt\":\"вчера\"}"))));
        assertEquals(List.of("/messageId:invalid_format"),
                errors(failure(() -> receive("{\"messageId\":\"\",\"status\":\"failed\",\"occurredAt\":\"2026-10-11T12:00:01.000Z\"}"))));
        assertEquals(List.of("/messageId:too_long"),
                errors(failure(() -> receive(notification("x".repeat(129), "delivered")))));
        assertEquals(List.of("/extra:unknown_value"), errors(failure(() -> receive(
                "{\"messageId\":\"1\",\"status\":\"failed\",\"occurredAt\":\"2026-10-11T12:00:01.000Z\",\"extra\":1}"))));
    }

    @Test
    void notJsonIsRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/webhooks/sms-provider");
        request.setContentType("text/plain");
        request.setContent("x".getBytes(StandardCharsets.UTF_8));

        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> controller.receive(request)).type());
    }
}
