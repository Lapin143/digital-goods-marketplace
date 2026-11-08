package dgm.platform.identity.otp.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.identity.otp.testing.OtpWorld;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Внутренняя операция {@code consumeEmailQuota} по схемам ConsumeEmailQuotaRequest и EmailQuotaGranted (OP-103, F4-1, Р-194). */
@Tag("TC-143")
class EmailQuotaControllerTest {

    private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");

    private OtpWorld world;
    private EmailQuotaController controller;

    @BeforeEach
    void setUp() {
        world = new OtpWorld(T0);
        controller = new EmailQuotaController(world.otp);
    }

    private static MockHttpServletRequest post(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/email-quota/consume");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private ResponseEntity<String> consume(String body) throws Exception {
        return controller.consume(post(body));
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
    void grantedAnswers200WithAllowedTrueAndNoStore() throws Exception {
        ResponseEntity<String> response = consume("{\"purpose\":\"vk_email\",\"email\":\"guest@mail.example\"}");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("allowed", true), Json.parseObject(response.getBody()));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(!response.getBody().contains("guest"), "в ответе адреса нет");
    }

    @Test
    void theFourthCallForTheSameAddressInTenMinutesAnswers429WithRetryAfterWhateverTheStep() throws Exception {
        consume("{\"purpose\":\"register\",\"email\":\"guest@mail.example\"}");
        consume("{\"purpose\":\"reset\",\"email\":\"guest@mail.example\"}");
        consume("{\"purpose\":\"vk_email\",\"email\":\"Guest@Mail.example\"}");

        ProblemException limited = failure(() -> consume("{\"purpose\":\"register\",\"email\":\"guest@mail.example\"}"));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        assertTrue(Long.parseLong(limited.headers().get("Retry-After")) > 0);
        assertEquals(200, consume("{\"purpose\":\"register\",\"email\":\"other@mail.example\"}").getStatusCode().value(), "другой адрес считается отдельно");
    }

    @Test
    void redisDownAnswers503AndNothingIsGranted() {
        world.store.down(true);

        ProblemException e = failure(() -> consume("{\"purpose\":\"register\",\"email\":\"guest@mail.example\"}"));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
    }

    @Test
    void badFieldsAreRefusedWith422BeforeTheCounterIsTouched() {
        assertEquals(List.of("/purpose:required"), errors(failure(() -> consume("{\"email\":\"guest@mail.example\"}"))));
        assertEquals(List.of("/purpose:unknown_value"), errors(failure(() -> consume("{\"purpose\":\"sms_login\",\"email\":\"guest@mail.example\"}"))));
        assertEquals(List.of("/email:required"), errors(failure(() -> consume("{\"purpose\":\"reset\"}"))));
        assertEquals(List.of("/email:invalid_format"), errors(failure(() -> consume("{\"purpose\":\"reset\",\"email\":\"guest\"}"))));
        assertEquals(List.of("/phone:unknown_value"), errors(failure(() -> consume("{\"purpose\":\"reset\",\"email\":\"guest@mail.example\",\"phone\":\"+79001234567\"}"))));
        assertTrue(world.store.keys().isEmpty(), "по негодному запросу счётчик не расходуется");
    }

    @Test
    void contentTypeAndBrokenBodyAreRefused() {
        MockHttpServletRequest text = post("x");
        text.setContentType("text/plain");
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, failure(() -> controller.consume(text)).type());
        assertEquals(ProblemType.BAD_REQUEST, failure(() -> consume("not json")).type());
    }
}
