package dgm.platform.support.controller;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.OPERATOR;
import static dgm.platform.support.testing.SupportWorld.OPERATOR_2;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.platform.support.domain.Operator;
import dgm.platform.support.testing.SupportWorld;
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

/**
 * Разбор запросов и формат ответов {@code listSupportTicketQueue}, {@code getStaffSupportTicket}, {@code takeSupportTicket} и
 * {@code releaseSupportTicket} по схеме StaffSupportTicket (Р-163). В ответе нет ни значений ключей, ни адресов, ни кодов (INV-23).
 */
@Tag("TC-704")
@Tag("TC-705")
@Tag("TC-707")
class StaffSupportControllerTest {

    private static final String BASE = "/api/v1/staff/support-tickets";
    private static final Set<String> FIELDS = Set.of("ticketId", "orderId", "orderNumber", "buyerId", "source", "reason", "status", "operatorId",
            "orderStatus", "issuedAt", "deliveryStatus", "createdAt", "takenAt", "resolvedAt", "emailChange");

    private SupportWorld world;
    private StaffSupportController controller;
    private UUID order;
    private UUID ticket;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
        controller = new StaffSupportController(world.queue);
        order = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)));
        ticket = world.tickets.create(BUYER, order).id();
    }

    private static MockHttpServletRequest request(String method, String uri, UUID user) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("203.0.113.7");
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser(user.toString(), Set.of("staff.support"), Set.of("support-operator"),
                List.of("pwd", "otp"), false));
        return request;
    }

    private static MockHttpServletRequest list(String... parameters) {
        MockHttpServletRequest request = request("GET", BASE, OPERATOR);
        for (int i = 0; i < parameters.length; i += 2) {
            request.addParameter(parameters[i], parameters[i + 1]);
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ProblemException e) {
        return (List<Map<String, Object>>) e.extensions().get("errors");
    }

    @Test
    void queueAnswersByTheSchemaWithNoStoreAndNothingPersonal() {
        ResponseEntity<String> response = controller.list(list());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = Json.parseObject(response.getBody());
        assertEquals(Set.of("items", "page"), body.keySet());
        List<?> items = (List<?>) body.get("items");
        assertEquals(1, items.size());
        Map<?, ?> row = (Map<?, ?>) items.get(0);
        assertEquals(FIELDS, row.keySet(), "поля ровно по схеме StaffSupportTicket");
        assertEquals(ticket.toString(), row.get("ticketId"));
        assertEquals(1000, ((Number) row.get("orderNumber")).intValue());
        assertEquals("created", row.get("status"));
        assertNull(row.get("operatorId"));
        assertNull(row.get("takenAt"));
        assertNull(row.get("emailChange"));
        assertEquals("2026-10-25T11:00:00.000Z", row.get("issuedAt"));
        assertEquals(20, ((Map<?, ?>) body.get("page")).get("limit"));
        assertNull(((Map<?, ?>) body.get("page")).get("nextCursor"));
        assertFalse(response.getBody().contains("@"), "адресов в очереди нет");
    }

    @Test
    void theQueueDefaultsToCreatedAndAnOrderNumberWidensItToAnyStatus() {
        world.queue.take(new Operator(OPERATOR, "203.0.113.7"), ticket);

        assertEquals(0, ((List<?>) Json.parseObject(controller.list(list()).getBody()).get("items")).size(), "по умолчанию только «создано»");
        assertEquals(1, ((List<?>) Json.parseObject(controller.list(list("status", "in_progress", "operatorId", OPERATOR.toString())).getBody()).get("items")).size());
        assertEquals(0, ((List<?>) Json.parseObject(controller.list(list("status", "in_progress", "operatorId", OPERATOR_2.toString())).getBody()).get("items")).size());
        assertEquals(1, ((List<?>) Json.parseObject(controller.list(list("orderNumber", "1000")).getBody()).get("items")).size(), "с номером заказа любой статус");
        assertEquals(0, ((List<?>) Json.parseObject(controller.list(list("orderNumber", "1000", "status", "created")).getBody()).get("items")).size());
        assertEquals(0, ((List<?>) Json.parseObject(controller.list(list("orderNumber", "999999")).getBody()).get("items")).size(), "неизвестный номер: пустая страница");
    }

    @Test
    void listChecksEveryParameterAndReportsAllMistakesAtOnce() {
        ProblemException e = assertThrows(ProblemException.class, () -> controller.list(list("status", "open", "operatorId", "x", "orderNumber", "0")));

        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(List.of("operatorId", "orderNumber", "status"), errors(e).stream().map(m -> (String) m.get("parameter")).sorted().toList());

        MockHttpServletRequest letters = list("orderNumber", "abc");
        assertEquals("invalid_format", errors(assertThrows(ProblemException.class, () -> controller.list(letters))).get(0).get("code"));
        MockHttpServletRequest unknown = list("buyerId", BUYER.toString());
        assertEquals("buyerId", errors(assertThrows(ProblemException.class, () -> controller.list(unknown))).get(0).get("parameter"));
        MockHttpServletRequest cursor = list("cursor", "AAAA");
        assertEquals("cursor", errors(assertThrows(ProblemException.class, () -> controller.list(cursor))).get(0).get("parameter"));
    }

    @Test
    void cardAnswersByTheSchemaAndAMalformedIdIsNotFound() {
        ResponseEntity<String> card = controller.get(ticket.toString());

        assertEquals(200, card.getStatusCode().value());
        assertEquals("no-store", card.getHeaders().getCacheControl());
        assertEquals(FIELDS, Json.parseObject(card.getBody()).keySet());
        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> controller.get("42")).type());
        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> controller.get(UUID.randomUUID().toString())).type());
    }

    @Test
    void takeAndReleaseAnswerWithTheTicketAndRecordTheCallerAddress() {
        MockHttpServletRequest take = request("POST", BASE + "/" + ticket + "/take", OPERATOR);
        ResponseEntity<String> taken = controller.take(ticket.toString(), take);

        assertEquals(200, taken.getStatusCode().value());
        assertEquals("no-store", taken.getHeaders().getCacheControl());
        Map<String, Object> body = Json.parseObject(taken.getBody());
        assertEquals("in_progress", body.get("status"));
        assertEquals(OPERATOR.toString(), body.get("operatorId"));
        assertEquals("2026-10-25T12:00:00.000Z", body.get("takenAt"));
        assertEquals("203.0.113.7", world.outbox.events("audit.recorded").get(0).data().get("actorIp"));

        ProblemException second = assertThrows(ProblemException.class,
                () -> controller.take(ticket.toString(), request("POST", BASE + "/" + ticket + "/take", OPERATOR_2)));
        assertEquals(ProblemType.STATE_CONFLICT, second.type());

        ResponseEntity<String> released = controller.release(ticket.toString(), request("POST", BASE + "/" + ticket + "/release", OPERATOR));
        Map<String, Object> back = Json.parseObject(released.getBody());
        assertEquals("created", back.get("status"));
        assertNull(back.get("operatorId"));
        assertNull(back.get("takenAt"));
        assertEquals(2, world.outbox.events("audit.recorded").size(), "взятие и возврат по одной записи аудита");
    }

    @Test
    void takeAndReleaseTreatAMalformedIdAsNotFound() {
        assertEquals(ProblemType.NOT_FOUND,
                assertThrows(ProblemException.class, () -> controller.take("not-an-id", request("POST", BASE + "/not-an-id/take", OPERATOR))).type());
        assertEquals(ProblemType.NOT_FOUND,
                assertThrows(ProblemException.class, () -> controller.release("not-an-id", request("POST", BASE + "/not-an-id/release", OPERATOR))).type());
    }
}
