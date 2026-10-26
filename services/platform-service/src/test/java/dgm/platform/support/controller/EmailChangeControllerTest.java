package dgm.platform.support.controller;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.BUYER_EMAIL;
import static dgm.platform.support.testing.SupportWorld.BUYER_PHONE;
import static dgm.platform.support.testing.SupportWorld.OPERATOR;
import static dgm.platform.support.testing.SupportWorld.OPERATOR_2;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.platform.support.domain.Operator;
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

/**
 * Разбор запросов и формат ответов {@code startEmailChange}, {@code getEmailChangeState}, {@code requestEmailChangeCode} и
 * {@code verifyEmailChangeCode} по схеме EmailChangeState (Р-169): закрытый JSON, ошибки одним ответом 422 без значений, 404 для неверной формы
 * идентификатора, 415 для чужого типа содержимого. В ответах оператору и покупателю нет ни кодов, ни адреса, только маска (FT-7.5, INV-23).
 */
@Tag("TC-711")
@Tag("TC-712")
@Tag("TC-714")
@Tag("TC-715")
class EmailChangeControllerTest {

    private static final String STAFF = "/api/v1/staff/support-tickets/";
    private static final String BUYER_BASE = "/api/v1/support-tickets/";
    private static final Set<String> FIELDS = Set.of("status", "phoneCheck", "newEmailCheck", "newEmailMasked", "startedAt", "updatedAt");
    private static final String NEW_EMAIL = "new.address@mail.example";

    private SupportWorld world;
    private EmailChangeController controller;
    private UUID ticket;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
        controller = new EmailChangeController(world.emailChange);
        UUID order = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)));
        ticket = world.tickets.create(BUYER, order).id();
        world.queue.take(new Operator(OPERATOR, "203.0.113.7"), ticket);
        world.scriptedCodes.then("111111", "222222", "333333");
    }

    private static MockHttpServletRequest request(String method, String uri, UUID user, Set<String> scopes, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("203.0.113.7");
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser(user.toString(), scopes, Set.of(role), List.of("pwd", "otp"), false));
        return request;
    }

    private static MockHttpServletRequest staff(String method, UUID id, UUID operator, String body) {
        MockHttpServletRequest request = request(method, STAFF + id + "/email-change", operator, Set.of("staff.support"), "support-operator");
        if (body != null) {
            request.setContentType("application/json");
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    private static MockHttpServletRequest buyer(UUID id, String path, String body) {
        MockHttpServletRequest request = request("POST", BUYER_BASE + id + "/email-change/" + path, BUYER, Set.of("support.write"), "buyer");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private ResponseEntity<String> start(String body) throws IOException {
        return controller.start(ticket.toString(), staff("POST", ticket, OPERATOR, body));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ProblemException e) {
        return (List<Map<String, Object>>) e.extensions().get("errors");
    }

    private static List<String> pointers(ProblemException e) {
        return errors(e).stream().map(m -> (String) m.get("pointer")).sorted().toList();
    }

    // ---- оператор ----

    @Test
    void startAnswersCreatedByTheSchemaWithTheLocationNoStoreAndOnlyTheMask() throws IOException {
        ResponseEntity<String> response = start("{\"newEmail\":\"  New.Address@Mail.Example \"}");

        assertEquals(201, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(STAFF + ticket + "/email-change", response.getHeaders().getFirst("Location"));
        Map<String, Object> body = Json.parseObject(response.getBody());
        assertEquals(FIELDS, body.keySet(), "поля ровно по схеме EmailChangeState");
        assertEquals("awaiting_phone_code", body.get("status"));
        assertEquals("pending", body.get("phoneCheck"));
        assertEquals("not_started", body.get("newEmailCheck"));
        assertEquals("n***@mail.example", body.get("newEmailMasked"));
        assertEquals("2026-10-25T12:00:00.000Z", body.get("startedAt"));
        assertFalse(response.getBody().contains("new.address"), "адреса оператору не показываем");
        assertFalse(response.getBody().contains("111111"), "кода оператору не показываем");
        assertFalse(response.getBody().contains(BUYER_PHONE), "номера нет");
        assertEquals(1, world.sms.sent().size());
        assertEquals("203.0.113.7", world.outbox.events("audit.recorded").get(world.outbox.events("audit.recorded").size() - 1).data().get("actorIp"),
                "адрес оператора берётся из запроса");
    }

    @Test
    void theAddressIsNormalizedAndTheLetterGoesToIt() throws IOException {
        start("{\"newEmail\":\"  New.Address@Mail.Example \"}");

        controller.verify(ticket.toString(), buyer(ticket, "verifications", "{\"step\":\"phone\",\"code\":\"111111\"}"));

        assertEquals(NEW_EMAIL, world.mail.last().orElseThrow().to());
    }

    @Test
    void getStateShowsTheResultOfTheChecksToTheOwningOperatorOnly() throws IOException {
        assertEquals(ProblemType.NOT_FOUND,
                assertThrows(ProblemException.class, () -> controller.state(ticket.toString(), staff("GET", ticket, OPERATOR, null))).type(),
                "смена не начата");
        start("{\"newEmail\":\"" + NEW_EMAIL + "\"}");

        ResponseEntity<String> own = controller.state(ticket.toString(), staff("GET", ticket, OPERATOR, null));

        assertEquals(200, own.getStatusCode().value());
        assertEquals("no-store", own.getHeaders().getCacheControl());
        Map<String, Object> body = Json.parseObject(own.getBody());
        assertEquals(FIELDS, body.keySet());
        assertEquals("awaiting_phone_code", body.get("status"));
        assertEquals(ProblemType.NOT_FOUND,
                assertThrows(ProblemException.class, () -> controller.state(ticket.toString(), staff("GET", ticket, OPERATOR_2, null))).type(),
                "обращение другого оператора");
    }

    @Test
    void aMalformedOrUnknownTicketIdIsNotFoundForEveryOperation() {
        String unknown = UUID.randomUUID().toString();

        for (String id : new String[] {"42", "not-an-id", unknown, unknown.toUpperCase(java.util.Locale.ROOT)}) {
            assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class,
                    () -> controller.start(id, staff("POST", ticket, OPERATOR, "{\"newEmail\":\"" + NEW_EMAIL + "\"}"))).type(), id);
            assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class,
                    () -> controller.state(id, staff("GET", ticket, OPERATOR, null))).type(), id);
            assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class,
                    () -> controller.requestCode(id, buyer(ticket, "codes", "{\"step\":\"phone\"}"))).type(), id);
            assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class,
                    () -> controller.verify(id, buyer(ticket, "verifications", "{\"step\":\"phone\",\"code\":\"111111\"}"))).type(), id);
        }
        assertEquals(0, world.sms.sent().size());
    }

    @Test
    void startWithoutTheTicketInWorkIsRefusedAsTicketNotInProgress() {
        UUID other = world.tickets.create(BUYER, world.issuedOrder(BUYER, T0.minus(Duration.ofHours(2)))).id();

        ProblemException e = assertThrows(ProblemException.class,
                () -> controller.start(other.toString(), staff("POST", other, OPERATOR, "{\"newEmail\":\"" + NEW_EMAIL + "\"}")));

        assertEquals(ProblemType.TICKET_NOT_IN_PROGRESS, e.type());
        assertEquals(0, world.sms.sent().size());
    }

    @Test
    void startChecksTheBodyAndReportsAllMistakesAtOnceWithoutValues() {
        ProblemException e = assertThrows(ProblemException.class, () -> start("{\"newEmail\":\"not-an-address\",\"extra\":1}"));

        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(List.of("/extra", "/newEmail"), pointers(e));
        assertFalse(e.getMessage().contains("not-an-address"), e.getMessage());
        assertFalse(errors(e).toString().contains("not-an-address"), errors(e).toString());

        assertEquals(List.of("/newEmail"), pointers(assertThrows(ProblemException.class, () -> start("{}"))));
        assertEquals("required", errors(assertThrows(ProblemException.class, () -> start("{}"))).get(0).get("code"));
        assertEquals("invalid_format", errors(assertThrows(ProblemException.class, () -> start("{\"newEmail\":5}"))).get(0).get("code"));
        assertEquals("invalid_format", errors(assertThrows(ProblemException.class, () -> start("{\"newEmail\":\"a@b\"}"))).get(0).get("code"));
        assertEquals("invalid_format", errors(assertThrows(ProblemException.class, () -> start("{\"newEmail\":\"a b@c.example\"}"))).get(0).get("code"));
        assertEquals("too_long", errors(assertThrows(ProblemException.class, () -> start("{\"newEmail\":\"" + "a".repeat(400) + "@mail.example\"}"))).get(0).get("code"));
        assertEquals(0, world.sms.sent().size(), "с неверным телом код не отправляется");
    }

    @Test
    void startRefusesAnotherContentTypeAndABrokenBody() {
        MockHttpServletRequest text = staff("POST", ticket, OPERATOR, "newEmail=" + NEW_EMAIL);
        text.setContentType("text/plain");
        MockHttpServletRequest none = request("POST", STAFF + ticket + "/email-change", OPERATOR, Set.of("staff.support"), "support-operator");

        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> controller.start(ticket.toString(), text)).type());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> controller.start(ticket.toString(), none)).type());
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> start("{\"newEmail\":")).type());
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> start("[]")).type());
        assertEquals(0, world.sms.sent().size());
    }

    @Test
    void theNeutralRefusalForATakenAddressDoesNotNameTheReasonOrTheAddress() {
        world.accounts.add(UUID.randomUUID(), "taken@example.test", "buyer", T0);

        ProblemException taken = assertThrows(ProblemException.class, () -> start("{\"newEmail\":\"taken@example.test\"}"));
        ProblemException own = assertThrows(ProblemException.class, () -> start("{\"newEmail\":\"" + BUYER_EMAIL + "\"}"));

        assertEquals(ProblemType.EMAIL_NOT_ALLOWED, taken.type());
        assertEquals(taken.type(), own.type());
        assertEquals(taken.getMessage(), own.getMessage());
        assertFalse(taken.getMessage().contains("taken@example.test"), taken.getMessage());
        assertFalse(own.getMessage().contains(BUYER_EMAIL), own.getMessage());
        assertEquals(Map.of(), taken.extensions());
        assertEquals(0, world.sms.sent().size());
    }

    // ---- покупатель ----

    @Test
    void theBuyerRequestsACodeAndVerifiesItWithAnswersByTheSchema() throws IOException {
        start("{\"newEmail\":\"" + NEW_EMAIL + "\"}");

        ResponseEntity<String> again = controller.requestCode(ticket.toString(), buyer(ticket, "codes", "{\"step\":\"phone\"}"));
        assertEquals(200, again.getStatusCode().value());
        assertEquals("no-store", again.getHeaders().getCacheControl());
        assertEquals(FIELDS, Json.parseObject(again.getBody()).keySet());
        assertEquals(2, world.sms.sent().size());

        ResponseEntity<String> verified = controller.verify(ticket.toString(), buyer(ticket, "verifications", "{\"step\":\"phone\",\"code\":\"222222\"}"));
        Map<String, Object> body = Json.parseObject(verified.getBody());
        assertEquals(200, verified.getStatusCode().value());
        assertEquals("no-store", verified.getHeaders().getCacheControl());
        assertEquals("awaiting_email_code", body.get("status"));
        assertEquals("passed", body.get("phoneCheck"));
        assertEquals("pending", body.get("newEmailCheck"));
        assertFalse(verified.getBody().contains("new.address"), verified.getBody());
        assertFalse(verified.getBody().contains("222222"), verified.getBody());
        assertNotNull(world.mail.last().orElse(null));

        ResponseEntity<String> done = controller.verify(ticket.toString(),
                buyer(ticket, "verifications", "{\"step\":\"new_email\",\"code\":\"" + world.mail.last().orElseThrow().code() + "\"}"));
        assertEquals("passed", Json.parseObject(done.getBody()).get("newEmailCheck"));
        assertEquals("completed", Json.parseObject(done.getBody()).get("status"), "верный код из письма завершает смену в том же ответе");
        assertFalse(done.getBody().contains("new.address"), done.getBody());
    }

    @Test
    void aWrongCodeAnswersInvalidWithTheAttemptsLeft() throws IOException {
        start("{\"newEmail\":\"" + NEW_EMAIL + "\"}");

        ProblemException e = assertThrows(ProblemException.class,
                () -> controller.verify(ticket.toString(), buyer(ticket, "verifications", "{\"step\":\"phone\",\"code\":\"000000\"}")));

        assertEquals(ProblemType.OTP_INVALID, e.type());
        assertEquals(4, ((Number) e.extensions().get("attemptsLeft")).intValue());
    }

    @Test
    void buyerBodiesAreClosedAndChecked() throws IOException {
        start("{\"newEmail\":\"" + NEW_EMAIL + "\"}");

        ProblemException none = assertThrows(ProblemException.class, () -> controller.verify(ticket.toString(), buyer(ticket, "verifications", "{}")));
        assertEquals(List.of("/code", "/step"), pointers(none));
        ProblemException format = assertThrows(ProblemException.class,
                () -> controller.verify(ticket.toString(), buyer(ticket, "verifications", "{\"step\":\"email\",\"code\":\"12345\"}")));
        assertEquals(List.of("/code", "/step"), pointers(format));
        assertFalse(errors(format).toString().contains("12345"), "значения кода в ошибках нет");
        ProblemException extra = assertThrows(ProblemException.class,
                () -> controller.requestCode(ticket.toString(), buyer(ticket, "codes", "{\"step\":\"phone\",\"code\":\"111111\"}")));
        assertEquals(List.of("/code"), pointers(extra), "в запросе кода поля code нет");
        assertEquals(List.of("/step"), pointers(assertThrows(ProblemException.class,
                () -> controller.requestCode(ticket.toString(), buyer(ticket, "codes", "{}")))));

        assertEquals(1, world.sms.sent().size(), "лишних SMS нет");
    }

    @Test
    void aStrangersTicketIsNotFoundForTheBuyerOperations() throws IOException {
        start("{\"newEmail\":\"" + NEW_EMAIL + "\"}");
        MockHttpServletRequest foreign = request("POST", BUYER_BASE + ticket + "/email-change/verifications", UUID.randomUUID(), Set.of("support.write"), "buyer");
        foreign.setContentType("application/json");
        foreign.setContent("{\"step\":\"phone\",\"code\":\"111111\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> controller.verify(ticket.toString(), foreign)).type());
    }

    // ---- карточки обращений ----

    @Test
    void bothTicketCardsShowTheStateWithoutTheAddress() throws IOException {
        StaffSupportController staffCards = new StaffSupportController(world.queue, world.resend);
        SupportTicketController buyerCards = new SupportTicketController(world.tickets);
        assertNull(Json.parseObject(staffCards.get(ticket.toString()).getBody()).get("emailChange"), "смена не начата");

        start("{\"newEmail\":\"" + NEW_EMAIL + "\"}");

        ResponseEntity<String> staffCard = staffCards.get(ticket.toString());
        Map<?, ?> staffState = (Map<?, ?>) Json.parseObject(staffCard.getBody()).get("emailChange");
        assertEquals(FIELDS, staffState.keySet());
        assertEquals("awaiting_phone_code", staffState.get("status"));
        assertFalse(staffCard.getBody().contains("new.address"), staffCard.getBody());

        ResponseEntity<String> buyerCard = buyerCards.get(ticket.toString(), request("GET", BUYER_BASE + ticket, BUYER, Set.of("support.write"), "buyer"));
        Map<?, ?> buyerState = (Map<?, ?>) Json.parseObject(buyerCard.getBody()).get("emailChange");
        assertEquals(FIELDS, buyerState.keySet());
        assertEquals("n***@mail.example", buyerState.get("newEmailMasked"));
        assertFalse(buyerCard.getBody().contains("new.address"), buyerCard.getBody());
    }
}
