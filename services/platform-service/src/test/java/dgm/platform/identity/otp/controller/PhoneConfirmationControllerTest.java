package dgm.platform.identity.otp.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.platform.identity.otp.testing.OtpWorld;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Разбор запросов и формат ответов подтверждения телефона по схемам CreatePhoneConfirmationRequest, PhoneConfirmation, VerifyCodeRequest, PhoneConfirmed. */
class PhoneConfirmationControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");

    private OtpWorld world;
    private PhoneConfirmationController controller;

    @BeforeEach
    void setUp() {
        world = new OtpWorld(T0);
        world.accounts.add(USER, "buyer@mail.example", "buyer", T0.minusSeconds(100));
        controller = new PhoneConfirmationController(world.confirmations);
    }

    private static MockHttpServletRequest post(String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setAttribute(JwtFilter.USER_ATTRIBUTE,
                new AuthenticatedUser(USER.toString(), Set.of("account.manage"), Set.of("buyer"), List.of("pwd"), false));
        request.setRemoteAddr("10.0.0.5");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private ResponseEntity<String> create(String body) throws Exception {
        return controller.create(post("/api/v1/phone-confirmations", body));
    }

    private static ProblemException failure(ThrowingCall call) {
        return assertThrows(ProblemException.class, call::run);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static List<String> errors(ProblemException e) {
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) e.extensions().get("errors")) {
            Map<?, ?> error = (Map<?, ?>) item;
            result.add(error.get("pointer") + ":" + error.get("code"));
        }
        return result;
    }

    // ---- createPhoneConfirmation ----

    @Test
    void createAnswers201WithThePhoneConfirmationSchemaAndLocation() throws Exception {
        world.codes.then("123456");

        ResponseEntity<String> response = create("{\"phone\":\"+79001234567\"}");

        assertEquals(201, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = Json.parseObject(response.getBody());
        assertEquals(Set.of("confirmationId", "phoneMasked", "expiresAt", "attemptsLeft", "resendAvailableAt"), body.keySet());
        assertEquals("+7900***4567", body.get("phoneMasked"));
        assertEquals("2026-10-11T12:05:00.000Z", body.get("expiresAt"));
        assertEquals(5, ((Number) body.get("attemptsLeft")).intValue());
        assertNull(body.get("resendAvailableAt"));
        assertEquals("/api/v1/phone-confirmations/" + body.get("confirmationId"), response.getHeaders().getLocation().toString());
        assertFalse(response.getBody().contains("123456"), "кода в ответе нет");
        assertFalse(response.getBody().contains("79001234567"), "полного номера в ответе нет");
    }

    @Test
    void createReportsWhenAResendHasToWait() throws Exception {
        for (int i = 0; i < 2; i++) {
            create("{\"phone\":\"+79001234567\"}");
        }

        Map<String, Object> third = Json.parseObject(create("{\"phone\":\"+79001234567\"}").getBody());

        assertEquals("2026-10-11T12:10:00.000Z", third.get("resendAvailableAt"));
    }

    @Test
    void createRejectsMissingMalformedAndExtraFieldsWithFieldErrors() {
        assertEquals(List.of("/phone:required"), errors(failure(() -> create("{}"))));
        assertEquals(List.of("/phone:invalid_format"), errors(failure(() -> create("{\"phone\":\"89001234567\"}"))));
        assertEquals(List.of("/phone:invalid_format"), errors(failure(() -> create("{\"phone\":\"+79001234567 \"}"))));
        assertEquals(List.of("/phone:invalid_format"), errors(failure(() -> create("{\"phone\":\"+380501234567\"}"))));
        assertEquals(List.of("/phone:invalid_format"), errors(failure(() -> create("{\"phone\":79001234567}"))));
        assertEquals(List.of("/extra:unknown_value"), errors(failure(() -> create("{\"phone\":\"+79001234567\",\"extra\":1}"))));
        ProblemException invalid = failure(() -> create("{}"));
        assertEquals(ProblemType.VALIDATION_FAILED, invalid.type());
        assertEquals(0, world.sms.count(), "ни один из отклонённых запросов SMS не отправил");
    }

    @Test
    void createRejectsAContentTypeThatIsNotJsonAndABrokenBody() {
        MockHttpServletRequest text = post("/api/v1/phone-confirmations", "phone=1");
        text.setContentType("text/plain");
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, failure(() -> controller.create(text)).type());

        assertEquals(ProblemType.BAD_REQUEST, failure(() -> create("{\"phone\":")).type());
        assertEquals(ProblemType.BAD_REQUEST, failure(() -> create("[]")).type());
    }

    @Test
    void createUsesTheFirstAddressOfForwardedForAndIgnoresGarbage() throws Exception {
        MockHttpServletRequest forwarded = post("/api/v1/phone-confirmations", "{\"phone\":\"+79001234567\"}");
        forwarded.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        controller.create(forwarded);
        assertTrue(world.store.keys().stream().anyMatch(key -> key.contains(":rl:ip:")));

        OtpWorld other = new OtpWorld(T0);
        other.accounts.add(USER, "buyer@mail.example", "buyer", T0.minusSeconds(100));
        MockHttpServletRequest garbage = post("/api/v1/phone-confirmations", "{\"phone\":\"+79001234567\"}");
        garbage.addHeader("X-Forwarded-For", "not an address <script>");
        new PhoneConfirmationController(other.confirmations).create(garbage);
        assertFalse(other.store.keys().stream().anyMatch(key -> key.contains(":rl:ip:")), "непохожее на адрес значение лимит по IP не создаёт");
    }

    // ---- verifyPhoneConfirmation ----

    @Test
    void verifyAnswers200WithThePhoneConfirmedSchema() throws Exception {
        world.codes.then("123456");
        Map<String, Object> created = Json.parseObject(create("{\"phone\":\"+79001234567\"}").getBody());

        ResponseEntity<String> response = controller.verify((String) created.get("confirmationId"),
                post("/api/v1/phone-confirmations/" + created.get("confirmationId") + "/verify", "{\"code\":\"123456\"}"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(Map.of("phoneConfirmed", true, "phoneMasked", "+7900***4567"), Json.parseObject(response.getBody()));
        assertTrue(world.accounts.find(USER).orElseThrow().phoneConfirmed());
    }

    @Test
    void verifyRejectsMalformedCodeBeforeSpendingAnAttempt() throws Exception {
        world.codes.then("123456");
        Map<String, Object> created = Json.parseObject(create("{\"phone\":\"+79001234567\"}").getBody());
        String id = (String) created.get("confirmationId");
        String uri = "/api/v1/phone-confirmations/" + id + "/verify";

        assertEquals(List.of("/code:invalid_format"), errors(failure(() -> controller.verify(id, post(uri, "{\"code\":\"12345\"}")))));
        assertEquals(List.of("/code:invalid_format"), errors(failure(() -> controller.verify(id, post(uri, "{\"code\":\"12345a\"}")))));
        assertEquals(List.of("/code:required"), errors(failure(() -> controller.verify(id, post(uri, "{}")))));
        assertEquals(List.of("/pin:unknown_value", "/code:required"), errors(failure(() -> controller.verify(id, post(uri, "{\"pin\":\"1\"}")))));

        ResponseEntity<String> ok = controller.verify(id, post(uri, "{\"code\":\"123456\"}"));
        assertEquals(200, ok.getStatusCode().value(), "все пять попыток целы");
    }

    @Test
    void verifyWithWrongCodeReportsAttemptsLeft() throws Exception {
        world.codes.then("123456");
        Map<String, Object> created = Json.parseObject(create("{\"phone\":\"+79001234567\"}").getBody());
        String id = (String) created.get("confirmationId");

        ProblemException wrong = failure(() -> controller.verify(id, post("/x", "{\"code\":\"000000\"}")));

        assertEquals(ProblemType.OTP_INVALID, wrong.type());
        assertEquals(4, wrong.extensions().get("attemptsLeft"));
    }

    @Test
    void verifyAnswers404ForAnIdentifierThatIsNotAUuidAndExpiredWhenThereIsNoCode() {
        assertEquals(ProblemType.NOT_FOUND, failure(() -> controller.verify("abc", post("/x", "{\"code\":\"123456\"}"))).type());
        assertEquals(ProblemType.OTP_EXPIRED,
                failure(() -> controller.verify(UUID.randomUUID().toString(), post("/x", "{\"code\":\"123456\"}"))).type(),
                "активного кода у учётной записи нет: он устарел или не запрашивался");
    }
}
