package dgm.order.orders.controller;

import static dgm.order.orders.FakeOrders.id;
import static dgm.order.orders.FakeOrders.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dgm.kit.problem.ProblemAdvice;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.order.orders.FakeOrders;
import dgm.order.orders.domain.Order;
import dgm.order.orders.service.OrderHistoryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** {@code listOrders}: владелец берётся из токена, адрес маскируется в сессии по SMS, параметры проверяются. */
class OrderControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00.250Z");
    private static final UUID ALICE = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID BOB = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");

    private FakeOrders orders;
    private MockMvc mvc;

    private static AuthenticatedUser buyer(UUID id, String... amr) {
        return new AuthenticatedUser(id.toString(), Set.of("orders.read"), Set.of("buyer"), List.of(amr), true);
    }

    private static MockHttpServletRequestBuilder as(AuthenticatedUser user, String url) {
        return get(url).requestAttr(JwtFilter.USER_ATTRIBUTE, user);
    }

    @BeforeEach
    void setUp() {
        Order paid = new Order(id(3), id(1003), "Игра", 1, 1_499_00L, 1_499_00L, "RUB", "paid", Optional.empty(), "email", "buyer@mail.example",
                Optional.empty(), Optional.empty(), T0.plusSeconds(120), Optional.of(T0.plusSeconds(150)), Optional.empty());
        Order cancelled = new Order(id(2), id(1002), "Подписка", 2, 500_00L, 1_000_00L, "RUB", "cancelled", Optional.of("payment_timeout"), "email",
                "buyer@mail.example", Optional.of(T0.plusSeconds(900)), Optional.of(T0.plusSeconds(1800)), T0.plusSeconds(60), Optional.empty(),
                Optional.empty());
        orders = new FakeOrders().with(ALICE, order(1, "issued", T0), cancelled, paid).with(BOB, order(4, "paid", T0.plusSeconds(30)));
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(new OrderHistoryService(orders))).setControllerAdvice(new ProblemAdvice()).build();
    }

    @Test
    void ownOrdersAreReturnedNewestFirstWithoutCaching() throws Exception {
        mvc.perform(as(buyer(ALICE, "pwd"), "/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].orderId").value(id(3).toString()))
                .andExpect(jsonPath("$.items[0].status").value("paid"))
                .andExpect(jsonPath("$.items[0].total.amount").value(149_900))
                .andExpect(jsonPath("$.items[0].unitPrice.currency").value("RUB"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-01T10:02:00.250Z"))
                .andExpect(jsonPath("$.items[0].paidAt").value("2026-10-01T10:02:30.250Z"))
                .andExpect(jsonPath("$.items[0].issuedAt").doesNotExist())
                .andExpect(jsonPath("$.page.limit").value(20));
        assertEquals(ALICE, orders.lastFilter().buyerId());
    }

    @Test
    void cancelledOrderCarriesReasonAndDeadlines() throws Exception {
        mvc.perform(as(buyer(ALICE, "pwd"), "/api/v1/orders"))
                .andExpect(jsonPath("$.items[1].orderId").value(id(2).toString()))
                .andExpect(jsonPath("$.items[1].cancelReason").value("payment_timeout"))
                .andExpect(jsonPath("$.items[1].quantity").value(2))
                .andExpect(jsonPath("$.items[1].reserveExpiresAt").value("2026-10-01T10:15:00.250Z"))
                .andExpect(jsonPath("$.items[1].paymentSessionExpiresAt").value("2026-10-01T10:30:00.250Z"));
    }

    @Test
    void ownerComesFromTokenNotFromRequest() throws Exception {
        mvc.perform(as(buyer(BOB, "pwd"), "/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value(id(4).toString()));
        mvc.perform(as(buyer(BOB, "pwd"), "/api/v1/orders?buyerId=" + ALICE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_value"));
    }

    @Test
    void addressIsFullInPasswordSession() throws Exception {
        mvc.perform(as(buyer(ALICE, "pwd"), "/api/v1/orders"))
                .andExpect(jsonPath("$.items[0].delivery.address").value("buyer@mail.example"))
                .andExpect(jsonPath("$.items[0].delivery.masked").value(false));
    }

    @Test
    void addressIsMaskedInSmsSession() throws Exception {
        mvc.perform(as(buyer(ALICE, "sms"), "/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].delivery.address").value("b***@mail.example"))
                .andExpect(jsonPath("$.items[0].delivery.masked").value(true))
                .andExpect(jsonPath("$.items[2].delivery.address").value("b***@mail.example"));
    }

    @Test
    void smsCombinedWithPasswordIsAFullSession() throws Exception {
        mvc.perform(as(buyer(ALICE, "pwd", "sms"), "/api/v1/orders"))
                .andExpect(jsonPath("$.items[0].delivery.masked").value(false));
    }

    @Test
    void noVerifiedUserMeansUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void subjectThatIsNotUuidMeansUnauthenticated() throws Exception {
        AuthenticatedUser odd = new AuthenticatedUser("alice", Set.of("orders.read"), Set.of("buyer"), List.of("pwd"), true);
        mvc.perform(as(odd, "/api/v1/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void statusAndPeriodFiltersReachTheService() throws Exception {
        mvc.perform(as(buyer(ALICE, "pwd"), "/api/v1/orders?status=issued,paid&createdFrom=2026-10-01T10:00:00Z&createdTo=2026-10-02T00:00:00Z&limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        var filter = orders.lastFilter();
        assertEquals(Optional.of(List.of("issued", "paid")), filter.statuses());
        assertEquals(Optional.of(Instant.parse("2026-10-01T10:00:00Z")), filter.createdFrom());
        assertEquals(Optional.of(Instant.parse("2026-10-02T00:00:00Z")), filter.createdTo());
        assertEquals(5, filter.limit());
    }

    @Test
    void unknownStatusIsRejected() throws Exception {
        mvc.perform(as(buyer(ALICE, "pwd"), "/api/v1/orders?status=lost"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].parameter").value("status"))
                .andExpect(jsonPath("$.errors[0].code").value("unknown_value"));
    }

    @Test
    void periodMustBeOrdered() throws Exception {
        mvc.perform(as(buyer(ALICE, "pwd"), "/api/v1/orders?createdFrom=2026-10-02T00:00:00Z&createdTo=2026-10-01T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].parameter").value("createdFrom"))
                .andExpect(jsonPath("$.errors[0].code").value("out_of_range"));
    }

    @Test
    void badInstantIsRejected() throws Exception {
        mvc.perform(as(buyer(ALICE, "pwd"), "/api/v1/orders?createdFrom=вчера"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("invalid_format"));
    }

    @Test
    void cursorContinuesTheHistory() throws Exception {
        MvcResult first = mvc.perform(as(buyer(ALICE, "pwd"), "/api/v1/orders?limit=2")).andExpect(status().isOk()).andReturn();
        String firstBody = first.getResponse().getContentAsString();
        String cursor = JsonPath.read(firstBody, "$.page.nextCursor");
        assertNotNull(cursor);

        String secondBody = mvc.perform(as(buyer(ALICE, "pwd"), "/api/v1/orders?limit=2&cursor=" + cursor)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        List<String> ids = JsonPath.read(secondBody, "$.items[*].orderId");
        assertEquals(List.of(id(1).toString()), ids);
        Object next = JsonPath.read(secondBody, "$.page.nextCursor");
        assertNull(next);
    }

    @Test
    void cursorWithGarbageIsRejected() throws Exception {
        mvc.perform(as(buyer(ALICE, "pwd"), "/api/v1/orders?cursor=AAAA"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].parameter").value("cursor"));
    }
}
