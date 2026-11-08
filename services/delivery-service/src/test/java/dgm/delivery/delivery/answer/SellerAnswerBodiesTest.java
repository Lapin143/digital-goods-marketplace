package dgm.delivery.delivery.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.service.SellerAnswerService.Answer;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Разбор тела {@code SellerAnswerRequest} (TC-969, OP-101): ровно одно из {@code keys} (1 – 10 строк до 1024 знаков) и {@code error} ({@code code} и
 * необязательное {@code message} до 500 знаков), лишнее поле это 422, не тот тип содержимого 415, не JSON 400, слишком большое тело 413. Значения ключей и
 * текст отказа в ответ об ошибке не попадают, названа только позиция.
 */
@Tag("TC-969")
class SellerAnswerBodiesTest {

    private static final String PATH = "/api/v1/seller-api/delivery-requests/0199e0a0-0000-7000-8000-000000000a01/answer";

    private static Map<String, Object> keys(Object... values) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keys", new ArrayList<>(Arrays.asList(values)));
        return body;
    }

    private static Map<String, Object> refusal(Object code, Object message) {
        Map<String, Object> error = new LinkedHashMap<>();
        if (code != null) {
            error.put("code", code);
        }
        if (message != null) {
            error.put("message", message);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> violations(Map<String, Object> body) {
        ProblemException e = assertThrows(ProblemException.class, () -> SellerAnswerBodies.parse(body));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        return (List<Map<String, Object>>) e.extensions().get("errors");
    }

    private static void assertViolation(Map<String, Object> body, String pointer, String code) {
        List<Map<String, Object>> errors = violations(body);
        assertTrue(errors.stream().anyMatch(error -> pointer.equals(error.get("pointer")) && code.equals(error.get("code"))),
                "ожидалось нарушение " + pointer + " " + code + ", получено " + errors);
    }

    @Test
    void aListOfKeysIsParsedAsTheAnswerWithKeysInTheirOrder() {
        Answer answer = SellerAnswerBodies.parse(keys("AAAAA-11111", "BBBBB-22222"));

        assertEquals(new Answer.Keys(List.of("AAAAA-11111", "BBBBB-22222")), answer);
    }

    @Test
    void theLimitsOfTheKeysAreTenKeysAndAThousandAndTwentyFourCharactersEach() {
        List<Object> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add("KEY-" + i);
        }
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("keys", ten);
        assertEquals(10, assertInstanceOf(Answer.Keys.class, SellerAnswerBodies.parse(full)).values().size());
        assertEquals(1, assertInstanceOf(Answer.Keys.class, SellerAnswerBodies.parse(keys("x".repeat(1024)))).values().size());

        ten.add("KEY-10");
        assertViolation(full, "/keys", "out_of_range");
        assertViolation(keys(), "/keys", "out_of_range");
        assertViolation(keys("x".repeat(1025)), "/keys/0", "too_long");
    }

    @Test
    void aBlankKeyAndAKeyOfAnotherTypeAreNamedByTheirPositionOnly() {
        assertViolation(keys("AAAAA-11111", "  "), "/keys/1", "too_short");
        assertViolation(keys(""), "/keys/0", "too_short");
        assertViolation(keys("AAAAA-11111", 7), "/keys/1", "invalid_format");
        assertViolation(keys((Object) null), "/keys/0", "invalid_format");
        Map<String, Object> notAList = new LinkedHashMap<>();
        notAList.put("keys", "AAAAA-11111");
        assertViolation(notAList, "/keys", "invalid_format");

        String text = violations(keys("AAAAA-11111", 7)).toString();
        assertFalse(text.contains("AAAAA"), "значение ключа в нарушениях не повторяется");
    }

    @Test
    void aRefusalWithACodeAndAMessageIsParsedAndTheMessageIsTrimmed() {
        assertEquals(new Answer.Refusal("out_of_stock", "Ключи закончились"), SellerAnswerBodies.parse(refusal("out_of_stock", "  Ключи закончились ")));
        assertEquals(new Answer.Refusal("cannot_issue", null), SellerAnswerBodies.parse(refusal("cannot_issue", null)));
        assertEquals(new Answer.Refusal("other", null), SellerAnswerBodies.parse(refusal("other", "   ")), "пустое пояснение это отсутствие пояснения");
        assertEquals(new Answer.Refusal("brand_new_code_7", null), SellerAnswerBodies.parse(refusal("brand_new_code_7", null)),
                "перечень кодов открыт, незнакомый код разбор пропускает");
        assertEquals(500, assertInstanceOf(Answer.Refusal.class, SellerAnswerBodies.parse(refusal("other", "м".repeat(500)))).message().length());
    }

    @Test
    void aRefusalThatBreaksTheSchemaIsNamedByItsFieldWithoutEchoingTheText() {
        assertViolation(refusal(null, "нет кода"), "/error/code", "required");
        assertViolation(refusal(5, null), "/error/code", "invalid_format");
        assertViolation(refusal("Out Of Stock", null), "/error/code", "invalid_format");
        assertViolation(refusal("a".repeat(65), null), "/error/code", "invalid_format");
        assertViolation(refusal("other", 5), "/error/message", "invalid_format");
        assertViolation(refusal("other", "м".repeat(501)), "/error/message", "too_long");
        Map<String, Object> notAnObject = new LinkedHashMap<>();
        notAnObject.put("error", "out_of_stock");
        assertViolation(notAnObject, "/error", "invalid_format");
        Map<String, Object> extra = refusal("other", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) extra.get("error");
        error.put("details", "x");
        assertViolation(extra, "/error/details", "unknown_value");

        assertFalse(violations(refusal("other", "м".repeat(501))).toString().contains("мммм"), "текст отказа в нарушениях не повторяется");
    }

    @Test
    void exactlyOneOfKeysAndErrorIsRequiredAndNothingElseIsAllowed() {
        Map<String, Object> both = keys("AAAAA-11111");
        both.putAll(refusal("other", null));
        assertViolation(both, "/error", "unknown_value");
        assertViolation(new LinkedHashMap<>(), "/keys", "required");

        Map<String, Object> extra = keys("AAAAA-11111");
        extra.put("note", "лишнее");
        assertViolation(extra, "/note", "unknown_value");
        Map<String, Object> extraRefusal = refusal("other", null);
        extraRefusal.put("keys", new ArrayList<>());
        assertTrue(violations(extraRefusal).size() >= 1);
    }

    @Test
    void theBodyIsReadFromTheRequestAfterTheContentTypeIsChecked() throws IOException {
        Answer answer = SellerAnswerBodies.read(request("application/json; charset=UTF-8", "{\"keys\": [\"AAAAA-11111\"]}"));
        assertEquals(new Answer.Keys(List.of("AAAAA-11111")), answer);
        assertEquals(new Answer.Refusal("out_of_stock", null), SellerAnswerBodies.read(request("application/json", "{\"error\": {\"code\": \"out_of_stock\"}}")));

        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> SellerAnswerBodies.read(request("application/json", "не JSON"))).type());
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> SellerAnswerBodies.read(request("application/json", "[1, 2]"))).type());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> SellerAnswerBodies.read(request("text/plain", "{}"))).type());
        MockHttpServletRequest missingType = new MockHttpServletRequest("POST", PATH);
        missingType.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> SellerAnswerBodies.read(missingType)).type());
    }

    @Test
    void aBodyOverTheLimitIsRefusedBeforeItIsParsed() {
        String huge = "{\"keys\": [\"" + "x".repeat(70_000) + "\"]}";

        ProblemException e = assertThrows(ProblemException.class, () -> SellerAnswerBodies.read(request("application/json", huge)));

        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, e.type());
        assertNull(e.extensions().get("errors"));
    }

    private static MockHttpServletRequest request(String contentType, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
