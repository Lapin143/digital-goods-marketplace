package dgm.platform.auditadmin.controller;

import static dgm.platform.auditadmin.testing.AuditViews.id;
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
import dgm.kit.time.ManualClock;
import dgm.platform.auditadmin.service.ParameterService;
import dgm.platform.auditadmin.testing.FakeParameterTransaction;
import dgm.platform.auditadmin.testing.InMemoryParameters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Разбор запросов и формат ответов {@code listPlatformParameters} и {@code setPlatformParameter} по схемам PlatformParameter и SetParameterRequest. */
@Tag("TC-806")
@Tag("TC-807")
class ParameterControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-25T15:00:00Z");
    private static final String BASE = "/api/v1/staff/parameters";
    private static final String RESERVE = "reservation.ttl-seconds";
    private static final Set<String> FIELDS = Set.of("key", "description", "unit", "value", "defaultValue", "minValue", "maxValue", "version", "updatedAt",
            "updatedBy");

    private InMemoryParameters parameters;
    private FakeParameterTransaction transaction;
    private ParameterController controller;

    @BeforeEach
    void setUp() {
        ManualClock clock = new ManualClock(NOW);
        parameters = InMemoryParameters.seeded(clock);
        transaction = new FakeParameterTransaction();
        controller = new ParameterController(new ParameterService(parameters, transaction, clock, new SimpleMeterRegistry()));
    }

    private static MockHttpServletRequest put(String key, String version, String body) {
        return put(key, version, body, Set.of("admin"));
    }

    private static MockHttpServletRequest put(String key, String version, String body, Set<String> roles) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", BASE + "/" + key);
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser(id(1).toString(), Set.of("staff.admin"), roles, List.of("pwd", "otp"), false));
        request.setRemoteAddr("10.0.0.5");
        request.setContentType("application/json");
        if (version != null) {
            request.addHeader("If-Match", version);
        }
        if (body != null) {
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    private ResponseEntity<String> set(String key, String version, String body) throws IOException {
        return controller.set(key, put(key, version, body));
    }

    private static Map<String, Object> body(ResponseEntity<String> response) {
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(response.getHeaders().getContentType().toString().startsWith("application/json"));
        return Json.parseObject(response.getBody());
    }

    private static ProblemException problem(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(ProblemException.class, action);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ProblemException e) {
        return (List<Map<String, Object>>) e.extensions().get("errors");
    }

    @Test
    void listFollowsThePlatformParameterListSchema() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", BASE);
        Map<String, Object> body = body(controller.list(request));

        List<?> items = (List<?>) body.get("items");
        assertEquals(3, items.size());
        assertEquals(Set.of("items"), body.keySet());
        for (Object item : items) {
            assertEquals(FIELDS, ((Map<?, ?>) item).keySet());
        }
        Map<?, ?> reserve = items.stream().map(i -> (Map<?, ?>) i).filter(i -> RESERVE.equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals("seconds", reserve.get("unit"));
        assertEquals(900, reserve.get("value"));
        assertEquals(900, reserve.get("defaultValue"));
        assertEquals(300, reserve.get("minValue"));
        assertEquals(3600, reserve.get("maxValue"));
        assertEquals(1, reserve.get("version"));
        assertEquals("2026-10-25T15:00:00.000Z", reserve.get("updatedAt"));
        assertNull(reserve.get("updatedBy"), "у начального значения автора нет");
    }

    @Test
    void listRejectsUnknownQueryParameters() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", BASE);
        request.addParameter("limit", "5");
        request.addParameter("x", "y");
        // limit и cursor известны всегда, лишним остаётся x
        assertEquals(ProblemType.VALIDATION_FAILED, problem(() -> controller.list(request)).type());
    }

    @Test
    void setAnswers200WithTheNewVersionAsETagAndTheWholeParameter() throws IOException {
        ResponseEntity<String> response = set(RESERVE, "\"1\"", "{\"value\":1200}");

        assertEquals("\"2\"", response.getHeaders().getETag());
        Map<String, Object> parameter = body(response);
        assertEquals(FIELDS, parameter.keySet());
        assertEquals(1200, parameter.get("value"));
        assertEquals(2, parameter.get("version"));
        assertEquals(id(1).toString(), parameter.get("updatedBy"));
        assertEquals(900, parameter.get("defaultValue"), "умолчание правка не трогает");
        assertEquals(List.of("audit.recorded", "config.changed"), transaction.published().stream().map(FakeParameterTransaction.Published::eventType).toList());
    }

    @Test
    void auditRecordCarriesTheAddressOfTheAdministrator() throws IOException {
        MockHttpServletRequest request = put(RESERVE, "\"1\"", "{\"value\":1200}");
        request.addHeader("X-Forwarded-For", "198.51.100.9, 10.0.0.5");

        controller.set(RESERVE, request);

        assertEquals("198.51.100.9", transaction.published("audit.recorded").get(0).data().get("actorIp"));
    }

    @Test
    void ifMatchIsRequiredAndMustBeAStrongETag() {
        assertEquals(ProblemType.PRECONDITION_REQUIRED, problem(() -> set(RESERVE, null, "{\"value\":1200}")).type());
        for (String bad : new String[] {"1", "W/\"1\"", "*", "\"abc\"", "\"-1\"", ""}) {
            ProblemException e = problem(() -> set(RESERVE, bad, "{\"value\":1200}"));
            assertTrue(e.type() == ProblemType.BAD_REQUEST || e.type() == ProblemType.PRECONDITION_REQUIRED, bad);
        }
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void staleVersionIsFourHundredAndTwelve() {
        assertEquals(ProblemType.VERSION_CONFLICT, problem(() -> set(RESERVE, "\"7\"", "{\"value\":1200}")).type());
    }

    @Test
    void unknownKeyIsNotFoundEvenWithABadBody() {
        assertEquals(ProblemType.NOT_FOUND, problem(() -> set("no.such-key", "\"1\"", "{\"value\":1200}")).type());
        assertEquals(ProblemType.NOT_FOUND, problem(() -> set("no.such-key", "\"1\"", "не json")).type());
    }

    @Test
    void notIntegerValuesAreRefusedWithTheBoundsInTheAnswer() {
        for (String bad : new String[] {"{\"value\":\"1200\"}", "{\"value\":1200.5}", "{\"value\":true}", "{\"value\":[1]}", "{\"value\":{}}",
                "{\"value\":1e3}", "{}", "{\"value\":null}", "{\"value\":99999999999999999999}", "{\"value\":1200,\"extra\":1}"}) {
            ProblemException e = problem(() -> set(RESERVE, "\"1\"", bad));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), bad);
            assertFalse(errors(e).isEmpty(), bad);
            for (Map<String, Object> error : errors(e)) {
                assertTrue(((String) error.get("pointer")).startsWith("/"), bad);
            }
        }
        ProblemException e = problem(() -> set(RESERVE, "\"1\"", "{\"value\":\"abc\"}"));
        assertTrue(((String) errors(e).get(0).get("detail")).contains("от 300 до 3600"), "нечисловое значение тоже получает границы");
        assertTrue(transaction.published().isEmpty());
        assertEquals(900, parameters.stored(RESERVE).value());
    }

    @Test
    void zeroAndNegativeValuesAreRefusedByTheBoundsOfTheParameter() {
        for (String bad : new String[] {"0", "-5", "299", "3601"}) {
            ProblemException e = problem(() -> set(RESERVE, "\"1\"", "{\"value\":" + bad + "}"));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), bad);
            assertTrue(((String) errors(e).get(0).get("detail")).contains("от 300 до 3600"), bad);
        }
    }

    @Test
    void brokenBodiesAndTypes() {
        assertEquals(ProblemType.BAD_REQUEST, problem(() -> set(RESERVE, "\"1\"", "не json")).type());
        assertEquals(ProblemType.BAD_REQUEST, problem(() -> set(RESERVE, "\"1\"", "[1200]")).type());
        MockHttpServletRequest text = put(RESERVE, "\"1\"", "{\"value\":1200}");
        text.setContentType("text/plain");
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.set(RESERVE, text)).type());
        String big = "{\"value\":1200,\"note\":\"" + "x".repeat(2000) + "\"}";
        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, problem(() -> set(RESERVE, "\"1\"", big)).type(), "тело больше 1 КБ не разбирается");
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void invariantSevenIsFourHundredAndTwentyTwoNamingThePairedParameter() {
        ProblemException e = problem(() -> set("payment-session.ttl-seconds", "\"1\"", "{\"value\":900}"));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertTrue(((String) errors(e).get(0).get("detail")).contains(RESERVE));
    }

    @Test
    void onlyAnAdministratorChangesParameters() {
        for (Set<String> roles : List.of(Set.of("moderator"), Set.of("support-operator"), Set.of("buyer"), Set.<String>of())) {
            ProblemException e = problem(() -> controller.set(RESERVE, put(RESERVE, "\"1\"", "{\"value\":1200}", roles)));
            assertEquals(ProblemType.FORBIDDEN, e.type(), roles.toString());
        }
        assertTrue(transaction.published().isEmpty());
        assertEquals(900, parameters.stored(RESERVE).value());
    }
}
