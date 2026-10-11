package dgm.platform.identity.controller;

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
import dgm.kit.web.PageCursor;
import dgm.platform.identity.service.StaffUserService;
import dgm.platform.identity.testing.FakeIdentityProvider;
import dgm.platform.identity.testing.FakeTransaction;
import dgm.platform.identity.testing.InMemoryAccounts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Разбор запросов и формат ответов операций сотрудников по схемам StaffUser, StaffUserPage, ChangeRoleRequest. */
class StaffUserControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID ADMIN = id(1);
    private static final UUID USER = id(2);

    private InMemoryAccounts accounts;
    private FakeIdentityProvider keycloak;
    private StaffUserController controller;

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-4000-8000-%012d", n));
    }

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccounts();
        FakeTransaction tx = new FakeTransaction();
        keycloak = new FakeIdentityProvider(tx);
        controller = new StaffUserController(new StaffUserService(accounts, keycloak, tx, new ManualClock(NOW)));
        accounts.add(ADMIN, "admin@mail.example", "admin", NOW.minusSeconds(1000));
        accounts.add(USER, "user@mail.example", "buyer", NOW.minusSeconds(500));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(JwtFilter.USER_ATTRIBUTE,
                new AuthenticatedUser(ADMIN.toString(), Set.of("staff.admin"), Set.of("admin"), List.of("pwd", "otp"), false));
        request.setRemoteAddr("10.0.0.5");
        return request;
    }

    private static MockHttpServletRequest json(String uri, String body) {
        MockHttpServletRequest request = request("POST", uri);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static Map<String, Object> body(ResponseEntity<String> response) {
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(response.getHeaders().getContentType().toString().startsWith("application/json"));
        return Json.parseObject(response.getBody());
    }

    private static ProblemException problem(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    @Test
    void listFollowsTheStaffUserPageSchema() {
        Map<String, Object> page = body(controller.list(request("GET", "/api/v1/staff/users")));

        List<?> items = (List<?>) page.get("items");
        assertEquals(2, items.size());
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals(Set.of("userId", "email", "name", "role", "status", "phoneConfirmed", "anonymized", "createdAt", "deactivatedAt"),
                first.keySet());
        assertEquals(USER.toString(), first.get("userId"));
        assertEquals("buyer", first.get("role"));
        assertEquals("active", first.get("status"));
        assertEquals(Boolean.FALSE, first.get("phoneConfirmed"));
        assertEquals("2026-10-11T11:51:40.000Z", first.get("createdAt"));
        assertNull(first.get("deactivatedAt"));
        Map<?, ?> info = (Map<?, ?>) page.get("page");
        assertEquals(20, ((Number) info.get("limit")).intValue());
        assertNull(info.get("nextCursor"));
    }

    @Test
    void cursorLeadsToTheNextPage() {
        MockHttpServletRequest first = request("GET", "/api/v1/staff/users");
        first.setParameter("limit", "1");
        Map<String, Object> page = body(controller.list(first));
        String cursor = (String) ((Map<?, ?>) page.get("page")).get("nextCursor");
        assertEquals(Set.of("t", "i"), PageCursor.decode(cursor).keySet());

        MockHttpServletRequest second = request("GET", "/api/v1/staff/users");
        second.setParameter("limit", "1");
        second.setParameter("cursor", cursor);
        Map<String, Object> next = body(controller.list(second));

        assertEquals(ADMIN.toString(), ((Map<?, ?>) ((List<?>) next.get("items")).get(0)).get("userId"));
        assertNull(((Map<?, ?>) next.get("page")).get("nextCursor"));
    }

    @Test
    void emailFilterIsExactAndCaseInsensitive() {
        MockHttpServletRequest request = request("GET", "/api/v1/staff/users");
        request.setParameter("email", " USER@Mail.example ");

        List<?> items = (List<?>) body(controller.list(request)).get("items");

        assertEquals(1, items.size());
        assertEquals(USER.toString(), ((Map<?, ?>) items.get(0)).get("userId"));
    }

    @Test
    void invalidFilterValuesGiveValidationErrorsPerParameter() {
        MockHttpServletRequest request = request("GET", "/api/v1/staff/users");
        request.setParameter("role", "root");
        request.setParameter("status", "gone");
        request.setParameter("limit", "0");
        request.setParameter("other", "1");

        ProblemException e = problem(() -> controller.list(request));

        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        List<?> errors = (List<?>) e.extensions().get("errors");
        Set<Object> parameters = new java.util.HashSet<>();
        for (Object error : errors) {
            parameters.add(((Map<?, ?>) error).get("parameter"));
        }
        assertEquals(Set.of("role", "status", "limit", "other"), parameters);
    }

    @Test
    void foreignCursorIsRejected() {
        MockHttpServletRequest request = request("GET", "/api/v1/staff/users");
        request.setParameter("cursor", PageCursor.encode(Map.of("t", "not-a-time", "i", "x")));

        assertEquals(ProblemType.VALIDATION_FAILED, problem(() -> controller.list(request)).type());
    }

    @Test
    void changeRoleReturnsTheUpdatedStaffUser() throws IOException {
        Map<String, Object> user = body(controller.changeRole(USER.toString(), json("/x", "{\"role\":\"moderator\"}")));

        assertEquals("moderator", user.get("role"));
        assertEquals(USER.toString(), user.get("userId"));
        assertEquals("user@mail.example", user.get("email"));
        assertFalse(user.containsKey("phone"));
    }

    @Test
    void changeRoleChecksTheBodyAgainstTheSchema() {
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> run(controller, USER.toString(), request("POST", "/x"))).type());
        assertEquals(ProblemType.BAD_REQUEST, problem(() -> run(controller, USER.toString(), json("/x", "[1]"))).type());
        assertEquals(ProblemType.BAD_REQUEST, problem(() -> run(controller, USER.toString(), json("/x", "{"))).type());

        for (String bad : List.of("{}", "{\"role\":\"root\"}", "{\"role\":5}", "{\"role\":null}", "{\"role\":\"admin\",\"extra\":1}")) {
            ProblemException e = problem(() -> run(controller, USER.toString(), json("/x", bad)));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), bad);
            assertFalse(((List<?>) e.extensions().get("errors")).isEmpty(), bad);
        }
        assertEquals("buyer", accounts.find(USER).orElseThrow().role());
    }

    @Test
    void roleSellerAndLastAdminAreConflictsOfTheContract() {
        assertEquals(ProblemType.ROLE_MANAGED_BY_SYSTEM,
                problem(() -> run(controller, USER.toString(), json("/x", "{\"role\":\"seller\"}"))).type());
        assertEquals(ProblemType.LAST_ADMIN_REQUIRED,
                problem(() -> run(controller, ADMIN.toString(), json("/x", "{\"role\":\"buyer\"}"))).type());
    }

    @Test
    void malformedIdentifierIsNotFoundLikeAnyMissingObject() {
        for (String bad : List.of("1", "not-a-uuid", "0199E0A0-0000-4000-8000-000000000002", "0199e0a0-0000-4000-8000-00000000000g")) {
            assertEquals(ProblemType.NOT_FOUND, problem(() -> controller.deactivate(bad, request("POST", "/x"))).type(), bad);
            assertEquals(ProblemType.NOT_FOUND, problem(() -> controller.anonymize(bad, request("POST", "/x"))).type(), bad);
            assertEquals(ProblemType.NOT_FOUND, problem(() -> run(controller, bad, json("/x", "{\"role\":\"moderator\"}"))).type(), bad);
        }
    }

    @Test
    void deactivateThenAnonymizeGiveTheDocumentedShapes() {
        Map<String, Object> deactivated = body(controller.deactivate(USER.toString(), request("POST", "/x")));
        assertEquals("deactivated", deactivated.get("status"));
        assertEquals("2026-10-11T12:00:00.000Z", deactivated.get("deactivatedAt"));
        assertEquals("user@mail.example", deactivated.get("email"));

        Map<String, Object> anonymized = body(controller.anonymize(USER.toString(), request("POST", "/x")));
        assertEquals(Boolean.TRUE, anonymized.get("anonymized"));
        assertNull(anonymized.get("name"));
        assertEquals("anonymized-" + USER + "@invalid", anonymized.get("email"));
    }

    @Test
    void auditAddressIsTheForwardedClientOtherwiseTheConnection() {
        MockHttpServletRequest direct = request("GET", "/x");
        assertEquals("10.0.0.5", StaffUserController.clientIp(direct));

        MockHttpServletRequest proxied = request("GET", "/x");
        proxied.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        assertEquals("203.0.113.9", StaffUserController.clientIp(proxied));

        MockHttpServletRequest blank = request("GET", "/x");
        blank.addHeader("X-Forwarded-For", "  ");
        assertEquals("10.0.0.5", StaffUserController.clientIp(blank));
    }

    private static void run(StaffUserController controller, String userId, MockHttpServletRequest request) {
        try {
            controller.changeRole(userId, request);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
