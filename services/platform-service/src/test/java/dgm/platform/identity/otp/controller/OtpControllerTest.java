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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Внутренние операции входа по SMS по схемам IssueOtpRequest, OtpIssueAccepted, VerifyOtpRequest, OtpVerified. */
class OtpControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");

    private OtpWorld world;
    private OtpController controller;

    @BeforeEach
    void setUp() {
        world = new OtpWorld(T0);
        world.accounts.add(BUYER, "buyer@mail.example", "buyer", T0.minusSeconds(100));
        world.accounts.confirmPhone(BUYER, "+79001234567");
        controller = new OtpController(world.logins);
    }

    private static MockHttpServletRequest post(String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private ResponseEntity<String> issue(String body) throws Exception {
        return controller.issue(post("/internal/v1/otp-codes", body));
    }

    private ResponseEntity<String> verify(String body) throws Exception {
        return controller.verify(post("/internal/v1/otp-verifications", body));
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

    // ---- issueOtpCode ----

    @Test
    void issueAnswersTheSameNeutralBodyForABoundAndAnUnknownNumber() throws Exception {
        world.codes.then("123456");

        ResponseEntity<String> bound = issue("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\",\"clientIp\":\"203.0.113.5\"}");
        ResponseEntity<String> unknown = issue("{\"purpose\":\"sms_login\",\"phone\":\"+79990000000\",\"clientIp\":\"203.0.113.5\"}");

        assertEquals(202, bound.getStatusCode().value());
        assertEquals(202, unknown.getStatusCode().value());
        assertEquals(bound.getBody(), unknown.getBody());
        assertEquals(Map.of("accepted", true), Json.parseObject(bound.getBody()));
        assertEquals(bound.getHeaders().toString(), unknown.getHeaders().toString());
        assertEquals("no-store", bound.getHeaders().getCacheControl());
        assertEquals(1, world.sms.count(), "SMS ушло только на привязанный номер");
    }

    @Test
    void issueWorksWithoutClientIp() throws Exception {
        assertEquals(202, issue("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\"}").getStatusCode().value());
        assertTrue(world.store.keys().stream().noneMatch(key -> key.contains(":rl:ip:")));
    }

    @Test
    void issueRejectsBadFieldsWithFieldErrors() {
        assertEquals(List.of("/purpose:required"), errors(failure(() -> issue("{\"phone\":\"+79001234567\"}"))));
        assertEquals(List.of("/purpose:unknown_value"),
                errors(failure(() -> issue("{\"purpose\":\"phone_confirm\",\"phone\":\"+79001234567\"}"))));
        assertEquals(List.of("/phone:required"), errors(failure(() -> issue("{\"purpose\":\"sms_login\"}"))));
        assertEquals(List.of("/phone:invalid_format"), errors(failure(() -> issue("{\"purpose\":\"sms_login\",\"phone\":\"89001234567\"}"))));
        assertEquals(List.of("/clientIp:too_long"), errors(failure(() -> issue(
                "{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\",\"clientIp\":\"" + "1".repeat(46) + "\"}"))));
        assertEquals(List.of("/clientIp:invalid_format"), errors(failure(() -> issue(
                "{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\",\"clientIp\":\"a b!\"}"))));
        assertEquals(List.of("/code:unknown_value"),
                errors(failure(() -> issue("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\",\"code\":\"1\"}"))));
        assertEquals(0, world.sms.count());
    }

    @Test
    void issueRejectsContentTypeAndBrokenBody() {
        MockHttpServletRequest text = post("/internal/v1/otp-codes", "x");
        text.setContentType("text/plain");
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, failure(() -> controller.issue(text)).type());
        assertEquals(ProblemType.BAD_REQUEST, failure(() -> issue("not json")).type());
    }

    @Test
    void issueAnswers429AfterTheLimitForTheNumber() throws Exception {
        for (int i = 0; i < 3; i++) {
            issue("{\"purpose\":\"sms_login\",\"phone\":\"+79990000000\"}");
        }

        ProblemException limited = failure(() -> issue("{\"purpose\":\"sms_login\",\"phone\":\"+79990000000\"}"));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        assertTrue(Long.parseLong(limited.headers().get("Retry-After")) > 0);
    }

    // ---- verifyOtpCode ----

    @Test
    void verifyAnswers200WithTheUserId() throws Exception {
        world.codes.then("123456");
        issue("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\"}");

        ResponseEntity<String> response = verify("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\",\"code\":\"123456\"}");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("userId", BUYER.toString()), Json.parseObject(response.getBody()));
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void verifyWrongCodeAndUnknownNumberGiveTheSame422() throws Exception {
        world.codes.then("123456");
        issue("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\"}");
        issue("{\"purpose\":\"sms_login\",\"phone\":\"+79990000000\"}");

        ProblemException known = failure(() -> verify("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\",\"code\":\"000000\"}"));
        ProblemException unknown = failure(() -> verify("{\"purpose\":\"sms_login\",\"phone\":\"+79990000000\",\"code\":\"000000\"}"));

        assertEquals(ProblemType.OTP_INVALID, known.type());
        assertEquals(known.type(), unknown.type());
        assertEquals(known.extensions(), unknown.extensions());
        assertEquals(known.getMessage(), unknown.getMessage());
    }

    @Test
    void verifyRejectsBadFieldsWithFieldErrors() {
        assertEquals(List.of("/code:required"), errors(failure(() -> verify("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\"}"))));
        assertEquals(List.of("/code:invalid_format"),
                errors(failure(() -> verify("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\",\"code\":\"12\"}"))));
        assertEquals(List.of("/purpose:unknown_value"),
                errors(failure(() -> verify("{\"purpose\":\"x\",\"phone\":\"+79001234567\",\"code\":\"123456\"}"))));
    }
}
