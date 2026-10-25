package dgm.platform.auditadmin.controller;

import static dgm.platform.auditadmin.testing.AuditViews.BASE;
import static dgm.platform.auditadmin.testing.AuditViews.id;
import static dgm.platform.auditadmin.testing.AuditViews.record;
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
import dgm.platform.auditadmin.domain.AuditFilter;
import dgm.platform.auditadmin.domain.AuditRecordView;
import dgm.platform.auditadmin.service.AuditViewService;
import dgm.platform.auditadmin.testing.InMemoryAuditReader;
import dgm.platform.auditadmin.testing.RecordingViewOutbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Разбор запроса и формат ответа {@code listAuditRecords} по схемам AuditRecord и AuditRecordPage. */
@Tag("TC-810")
class AuditControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-25T15:00:00Z");
    private static final String URI = "/api/v1/staff/audit-records";

    private InMemoryAuditReader reader;
    private RecordingViewOutbox outbox;
    private AuditController controller;

    @BeforeEach
    void setUp() {
        reader = new InMemoryAuditReader();
        outbox = new RecordingViewOutbox();
        controller = new AuditController(new AuditViewService(reader, outbox, new ManualClock(NOW), new SimpleMeterRegistry()));
        for (int n = 1; n <= 5; n++) {
            reader.add(record(n));
        }
    }

    private static MockHttpServletRequest request(String... parameters) {
        return request(Set.of("admin"), parameters);
    }

    private static MockHttpServletRequest request(Set<String> roles, String... parameters) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", URI);
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser(id(1).toString(), Set.of("staff.audit"), roles, List.of("pwd", "otp"), false));
        request.setRemoteAddr("10.0.0.5");
        for (int i = 0; i < parameters.length; i += 2) {
            request.addParameter(parameters[i], parameters[i + 1]);
        }
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
    void pageFollowsTheAuditRecordPageSchema() {
        Map<String, Object> page = body(controller.list(request()));

        List<?> items = (List<?>) page.get("items");
        assertEquals(5, items.size());
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals(Set.of("recordId", "occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes", "actorIp", "actorIpHashed",
                "correlationId"), first.keySet());
        assertEquals(id(5).toString(), first.get("recordId"));
        assertEquals("2026-10-25T12:00:05.000Z", first.get("occurredAt"));
        assertEquals("moderator", first.get("actorRole"));
        assertEquals("203.0.113.7", first.get("actorIp"));
        assertEquals(false, first.get("actorIpHashed"));
        Map<?, ?> change = (Map<?, ?>) ((List<?>) first.get("changes")).get(0);
        assertEquals(Set.of("field", "personal", "before", "after"), change.keySet());
        assertEquals("on_review", change.get("before"));
        Map<?, ?> info = (Map<?, ?>) page.get("page");
        assertEquals(20, info.get("limit"));
        assertNull(info.get("nextCursor"));
        for (int i = 1; i < items.size(); i++) {
            assertTrue(((String) ((Map<?, ?>) items.get(i - 1)).get("occurredAt")).compareTo((String) ((Map<?, ?>) items.get(i)).get("occurredAt")) > 0,
                    "новые выше");
        }
    }

    @Test
    void numbersAndBooleansOfTheJournalAreShownAsStringsAndMissingValuesAsNull() {
        reader.add(new AuditRecordView(id(6), BASE.plusSeconds(6), id(900), "admin", "parameter.changed", "parameter", "reservation.ttl-seconds",
                List.of(new AuditRecordView.Change("value", false, "900", "1200"), new AuditRecordView.Change("phone", true, null, "a".repeat(64))), "", false,
                id(5006)));

        Map<?, ?> first = (Map<?, ?>) ((List<?>) body(controller.list(request())).get("items")).get(0);

        assertEquals("", first.get("actorIp"));
        List<?> changes = (List<?>) first.get("changes");
        assertEquals("1200", ((Map<?, ?>) changes.get(0)).get("after"));
        assertNull(((Map<?, ?>) changes.get(1)).get("before"));
        assertEquals(true, ((Map<?, ?>) changes.get(1)).get("personal"));
    }

    @Test
    void cursorLeadsToTheNextPageAndHasNoSecondRecordOfTheView() {
        MockHttpServletRequest first = request("limit", "2");
        Map<String, Object> page = body(controller.list(first));
        String cursor = (String) ((Map<?, ?>) page.get("page")).get("nextCursor");
        assertEquals(Set.of("t", "i"), PageCursor.decode(cursor).keySet());

        MockHttpServletRequest second = request("limit", "2", "cursor", cursor);
        List<?> items = (List<?>) body(controller.list(second)).get("items");

        assertEquals(id(3).toString(), ((Map<?, ?>) items.get(0)).get("recordId"));
        assertEquals(1, outbox.events().size());
    }

    @Test
    void filtersAreHandedOverAsGiven() {
        controller.list(request("actorId", id(902).toString(), "objectType", "seller", "objectId", "abc", "action", "seller.approved",
                "occurredFrom", "2026-10-25T12:00:01Z", "occurredTo", "2026-10-25T12:30:00+03:00"));

        AuditFilter filter = reader.requests().get(0);
        assertEquals(id(902), filter.actorId().orElseThrow());
        assertEquals("seller", filter.objectType().orElseThrow());
        assertEquals("abc", filter.objectId().orElseThrow());
        assertEquals("seller.approved", filter.action().orElseThrow());
        assertEquals(Instant.parse("2026-10-25T12:00:01Z"), filter.occurredFrom().orElseThrow());
        assertEquals(Instant.parse("2026-10-25T09:30:00Z"), filter.occurredTo().orElseThrow());
        assertEquals(20, filter.limit());
    }

    @Test
    void filterNarrowsTheResult() {
        List<?> items = (List<?>) body(controller.list(request("action", "seller.approved"))).get("items");
        assertEquals(2, items.size());
        assertTrue(((List<?>) body(controller.list(request("objectType", "payment"))).get("items")).isEmpty());
    }

    @Test
    void badParametersAreCollectedIntoOneValidationFailure() {
        ProblemException e = problem(() -> controller.list(request("actorId", "not-an-id", "occurredFrom", "вчера", "limit", "0", "unknown", "x",
                "objectType", "t".repeat(65))));

        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        List<?> errors = (List<?>) e.extensions().get("errors");
        Set<Object> named = new java.util.HashSet<>();
        for (Object error : errors) {
            named.add(((Map<?, ?>) error).get("parameter"));
        }
        assertEquals(Set.of("actorId", "occurredFrom", "limit", "unknown", "objectType"), named);
        assertTrue(reader.requests().isEmpty());
        assertTrue(outbox.events().isEmpty(), "запрос, не прошедший проверку, журнал не открывает");
    }

    @Test
    void rangeMustHaveTheEndAfterTheStart() {
        for (String[] range : new String[][] {{"2026-10-25T12:00:00Z", "2026-10-25T12:00:00Z"}, {"2026-10-25T12:00:01Z", "2026-10-25T12:00:00Z"}}) {
            ProblemException e = problem(() -> controller.list(request("occurredFrom", range[0], "occurredTo", range[1])));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type());
            assertEquals("occurredTo", ((Map<?, ?>) ((List<?>) e.extensions().get("errors")).get(0)).get("parameter"));
        }
    }

    @Test
    void foreignCursorIsAValidationFailure() {
        for (String bad : new String[] {"abc", PageCursor.encode(Map.of("t", "not-a-time", "i", "x")), PageCursor.encode(Map.of("t", "2026-10-25T12:00:00Z"))}) {
            ProblemException e = problem(() -> controller.list(request("cursor", bad)));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), bad);
            assertEquals("cursor", ((Map<?, ?>) ((List<?>) e.extensions().get("errors")).get(0)).get("parameter"));
        }
        assertTrue(outbox.events().isEmpty());
    }

    @Test
    void repeatedParameterIsAnError() {
        MockHttpServletRequest request = request("action", "a");
        request.addParameter("action", "b");
        assertEquals(ProblemType.VALIDATION_FAILED, problem(() -> controller.list(request)).type());
    }

    @Test
    void viewIsRecordedWithTheAddressFromTheGatewayHeader() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "198.51.100.9, 10.0.0.5");

        controller.list(request);

        assertEquals("198.51.100.9", outbox.events().get(0).data().get("actorIp"));
        assertEquals("admin", outbox.events().get(0).data().get("actorRole"));
        assertEquals(id(1).toString(), outbox.events().get(0).data().get("actorId"));
    }

    @Test
    void onlyAnAdministratorReadsTheJournal() {
        for (Set<String> roles : List.of(Set.of("moderator"), Set.of("support-operator"), Set.of("buyer"), Set.<String>of())) {
            ProblemException e = problem(() -> controller.list(request(roles)));
            assertEquals(ProblemType.FORBIDDEN, e.type(), roles.toString());
        }
        assertTrue(outbox.events().isEmpty());
        assertTrue(reader.requests().isEmpty());
    }

    @Test
    void failedRecordOfTheViewGivesNoRecordsInTheAnswer() {
        outbox.failWith(new IllegalStateException("недоступно"));
        ProblemException e = problem(() -> controller.list(request()));
        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertFalse(e.headers().isEmpty());
    }
}
