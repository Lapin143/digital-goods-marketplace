package dgm.platform.support.controller;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.STRANGER;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.platform.support.testing.SupportWorld;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Разбор запросов и формат ответов {@code createSupportTicket}, {@code listSupportTickets} и {@code getSupportTicket} по схемам SupportTicket и CreateSupportTicketRequest. */
@Tag("TC-701")
class SupportTicketControllerTest {

    private static final String BASE = "/api/v1/support-tickets";
    private static final Set<String> FIELDS = Set.of("ticketId", "orderId", "source", "reason", "status", "createdAt", "resolvedAt", "resolution", "emailChange");

    private SupportWorld world;
    private SupportTicketController controller;
    private UUID order;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
        controller = new SupportTicketController(world.tickets);
        order = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)));
    }

    private static MockHttpServletRequest request(String method, String uri, UUID user, String... amr) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser(user.toString(), Set.of("support.write"), Set.of("buyer"),
                List.of(amr.length == 0 ? new String[] {"pwd"} : amr), false));
        return request;
    }

    private static MockHttpServletRequest post(String body) {
        MockHttpServletRequest request = request("POST", BASE, BUYER);
        request.setContentType("application/json");
        if (body != null) {
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    private String create() throws IOException {
        return create(order);
    }

    private String create(UUID orderId) throws IOException {
        ResponseEntity<String> response = controller.create(post("{\"orderId\":\"" + orderId + "\",\"reason\":\"key_not_received\"}"));
        return String.valueOf(Json.parseObject(response.getBody()).get("ticketId"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ProblemException e) {
        return (List<Map<String, Object>>) e.extensions().get("errors");
    }

    @Test
    void createAnswersWithTheTicketLocationAndNoStore() throws IOException {
        ResponseEntity<String> response = controller.create(post("{\"orderId\":\"" + order + "\",\"reason\":\"key_not_received\"}"));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = Json.parseObject(response.getBody());
        assertEquals(FIELDS, body.keySet(), "поля ровно по схеме SupportTicket, ключей, адресов и кодов нет");
        assertEquals(BASE + "/" + body.get("ticketId"), response.getHeaders().getLocation().toString());
        assertEquals(order.toString(), body.get("orderId"));
        assertEquals("buyer", body.get("source"));
        assertEquals("key_not_received", body.get("reason"));
        assertEquals("created", body.get("status"));
        assertEquals("2026-10-25T12:00:00.000Z", body.get("createdAt"));
        assertNull(body.get("resolvedAt"));
        assertNull(body.get("resolution"));
        assertNull(body.get("emailChange"));
    }

    @Test
    void createValidatesTheBodyAndReportsEveryMistakeAtOnce() {
        ProblemException e = assertThrows(ProblemException.class, () -> controller.create(post("{\"orderId\":\"not-a-uuid\",\"reason\":\"lost\",\"note\":\"x\"}")));

        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(List.of("/note", "/orderId", "/reason"), errors(e).stream().map(m -> (String) m.get("pointer")).sorted().toList());
        assertEquals(List.of("invalid_format", "unknown_value", "unknown_value"), errors(e).stream().map(m -> (String) m.get("code")).sorted().toList());
    }

    @Test
    void createNeedsBothFields() {
        ProblemException e = assertThrows(ProblemException.class, () -> controller.create(post("{}")));

        assertEquals(List.of("required", "required"), errors(e).stream().map(m -> (String) m.get("code")).toList());
        assertEquals(List.of("/orderId", "/reason"), errors(e).stream().map(m -> (String) m.get("pointer")).toList());
    }

    @Test
    void createRefusesAWrongContentTypeAndABrokenBody() {
        MockHttpServletRequest text = request("POST", BASE, BUYER);
        text.setContentType("text/plain");
        text.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> controller.create(text)).type());

        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> controller.create(post("[1"))).type());
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> controller.create(post("[]"))).type(), "массив вместо объекта");
    }

    @Test
    void createForAnotherBuyersOrderIsNotFound() {
        UUID foreign = world.issuedOrder(STRANGER, T0.minus(Duration.ofHours(1)));

        ProblemException e = assertThrows(ProblemException.class, () -> create(foreign));

        assertEquals(ProblemType.NOT_FOUND, e.type());
    }

    @Test
    void listFollowsTheSchemaAndPagesWithTheCursorFromTheAnswer() throws IOException {
        String first = create();
        world.clock.advance(Duration.ofMinutes(1));
        String second = create(world.issuedOrder(BUYER, T0.minus(Duration.ofHours(2))));

        MockHttpServletRequest page = request("GET", BASE, BUYER);
        page.addParameter("limit", "1");
        ResponseEntity<String> one = controller.list(page);
        Map<String, Object> body = Json.parseObject(one.getBody());
        assertEquals(200, one.getStatusCode().value());
        assertEquals("no-store", one.getHeaders().getCacheControl());
        assertEquals(Set.of("items", "page"), body.keySet());
        List<?> items = (List<?>) body.get("items");
        assertEquals(1, items.size());
        assertEquals(second, ((Map<?, ?>) items.get(0)).get("ticketId"), "новые выше");
        Map<?, ?> info = (Map<?, ?>) body.get("page");
        assertEquals(1, info.get("limit"));
        String cursor = (String) info.get("nextCursor");
        assertTrue(cursor != null && !cursor.isEmpty());

        MockHttpServletRequest next = request("GET", BASE, BUYER);
        next.addParameter("limit", "1");
        next.addParameter("cursor", cursor);
        Map<String, Object> rest = Json.parseObject(controller.list(next).getBody());
        assertEquals(first, ((Map<?, ?>) ((List<?>) rest.get("items")).get(0)).get("ticketId"));
        assertNull(((Map<?, ?>) rest.get("page")).get("nextCursor"));
    }

    @Test
    void listChecksItsParameters() {
        MockHttpServletRequest unknown = request("GET", BASE, BUYER);
        unknown.addParameter("buyerId", STRANGER.toString());
        assertEquals("buyerId", errors(assertThrows(ProblemException.class, () -> controller.list(unknown))).get(0).get("parameter"),
                "чужого покупателя в запросе указать нельзя: такого параметра нет");

        MockHttpServletRequest badStatus = request("GET", BASE, BUYER);
        badStatus.addParameter("status", "created,done");
        assertEquals("unknown_value", errors(assertThrows(ProblemException.class, () -> controller.list(badStatus))).get(0).get("code"));

        MockHttpServletRequest badOrder = request("GET", BASE, BUYER);
        badOrder.addParameter("orderId", "42");
        assertEquals("invalid_format", errors(assertThrows(ProblemException.class, () -> controller.list(badOrder))).get(0).get("code"));

        MockHttpServletRequest badCursor = request("GET", BASE, BUYER);
        badCursor.addParameter("cursor", "AAAA");
        assertEquals("cursor", errors(assertThrows(ProblemException.class, () -> controller.list(badCursor))).get(0).get("parameter"));
    }

    @Test
    void getReturnsOwnTicketAndTreatsAMalformedOrForeignIdAsNotFound() throws IOException {
        String ticketId = create();

        ResponseEntity<String> own = controller.get(ticketId, request("GET", BASE + "/" + ticketId, BUYER));
        assertEquals(200, own.getStatusCode().value());
        assertEquals(FIELDS, Json.parseObject(own.getBody()).keySet());

        MockHttpServletRequest foreign = request("GET", BASE + "/" + ticketId, STRANGER);
        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> controller.get(ticketId, foreign)).type());
        MockHttpServletRequest malformed = request("GET", BASE + "/42", BUYER);
        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> controller.get("42", malformed)).type());
    }

    @Test
    void aSolvedTicketShowsWhoSolvedItAndHow() throws IOException {
        String ticketId = create();
        world.repository.resolveByOperator(UUID.fromString(ticketId), "resend", T0.plusSeconds(60));

        Map<String, Object> body = Json.parseObject(controller.get(ticketId, request("GET", BASE + "/" + ticketId, BUYER)).getBody());

        assertEquals("resolved", body.get("status"));
        assertEquals("2026-10-25T12:01:00.000Z", body.get("resolvedAt"));
        assertEquals(Map.of("resolvedBy", "operator", "action", "resend"), body.get("resolution"));
    }

    @Test
    void aTicketSolvedByDeliveryHasNoAction() throws IOException {
        String ticketId = create();
        world.events.onDeliveryDelivered(order, T0.plusSeconds(30));

        Map<String, Object> body = Json.parseObject(controller.get(ticketId, request("GET", BASE + "/" + ticketId, BUYER)).getBody());

        assertEquals("resolved", body.get("status"));
        Map<?, ?> resolution = (Map<?, ?>) body.get("resolution");
        assertEquals("system", resolution.get("resolvedBy"));
        assertNull(resolution.get("action"));
    }
}
