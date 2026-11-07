package dgm.order.orders.controller;

import static dgm.order.orders.FakeOrders.id;
import static dgm.order.orders.FakeOrders.sellerOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import dgm.order.orders.domain.SellerOrder;
import dgm.order.orders.service.SellerOrderService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code listSellerOrders}: продавец берётся из токена или из личности по API-ключу, чужие заказы не видны, в ответе нет полей покупателя (INV-44),
 * параметры проверяются, ответ не кэшируется.
 */
@Tag("TC-961")
class SellerOrderControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00.250Z");
    private static final UUID ANNA = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID IVAN = UUID.fromString("0199e0a0-0000-7000-8000-000000000102");
    private static final Set<String> FIELDS = Set.of("orderId", "orderNumber", "productId", "productTitle", "quantity", "unitPrice", "total", "commission",
            "status", "cancelReason", "issuanceMethod", "createdAt", "paidAt", "issuedAt", "refundedAt");

    private FakeOrders orders;
    private MockMvc mvc;

    private static AuthenticatedUser seller(UUID profile) {
        return new AuthenticatedUser(UUID.randomUUID().toString(), Set.of("seller.catalog"), Set.of("seller"), List.of("pwd", "otp"), true, profile.toString());
    }

    private static MockHttpServletRequestBuilder as(AuthenticatedUser user, String url) {
        return get(url).requestAttr(JwtFilter.USER_ATTRIBUTE, user);
    }

    @BeforeEach
    void setUp() {
        SellerOrder timedOut = new SellerOrder(id(2), 1002L, id(1002), "Подписка по API", 2, 500_00L, 1_000_00L, 20_00L, "RUB", "refunded",
                Optional.of("seller_api_timeout"), "seller_api", T0.plusSeconds(60), Optional.of(T0.plusSeconds(90)), Optional.empty(),
                Optional.of(T0.plusSeconds(1000)));
        SellerOrder issued = new SellerOrder(id(3), 1003L, id(1003), "Игра", 1, 1_499_00L, 1_499_00L, 29_98L, "RUB", "issued", Optional.empty(), "key_pool",
                T0.plusSeconds(120), Optional.of(T0.plusSeconds(150)), Optional.of(T0.plusSeconds(180)), Optional.empty());
        orders = new FakeOrders().withSeller(ANNA, sellerOrder(1, "paid", T0), timedOut, issued).withSeller(IVAN, sellerOrder(4, "paid", T0.plusSeconds(30)));
        mvc = MockMvcBuilders.standaloneSetup(new SellerOrderController(new SellerOrderService(orders))).setControllerAdvice(new ProblemAdvice()).build();
    }

    @Test
    void ownOrdersAreReturnedNewestFirstWithoutCaching() throws Exception {
        mvc.perform(as(seller(ANNA), "/api/v1/seller/orders"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].orderId").value(id(3).toString()))
                .andExpect(jsonPath("$.items[0].orderNumber").value(1003))
                .andExpect(jsonPath("$.items[0].productTitle").value("Игра"))
                .andExpect(jsonPath("$.items[0].total.amount").value(149_900))
                .andExpect(jsonPath("$.items[0].commission.amount").value(2_998))
                .andExpect(jsonPath("$.items[0].commission.currency").value("RUB"))
                .andExpect(jsonPath("$.items[0].status").value("issued"))
                .andExpect(jsonPath("$.items[0].issuanceMethod").value("key_pool"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-01T10:02:00.250Z"))
                .andExpect(jsonPath("$.items[0].paidAt").value("2026-10-01T10:02:30.250Z"))
                .andExpect(jsonPath("$.items[0].issuedAt").value("2026-10-01T10:03:00.250Z"))
                .andExpect(jsonPath("$.items[0].refundedAt").doesNotExist())
                .andExpect(jsonPath("$.page.limit").value(20));
        assertEquals(ANNA, orders.lastSellerFilter().sellerId());
    }

    @Test
    void aTimedOutOrderShowsTheReasonTheMethodAndTheMomentOfTheRefund() throws Exception {
        mvc.perform(as(seller(ANNA), "/api/v1/seller/orders"))
                .andExpect(jsonPath("$.items[1].orderId").value(id(2).toString()))
                .andExpect(jsonPath("$.items[1].status").value("refunded"))
                .andExpect(jsonPath("$.items[1].cancelReason").value("seller_api_timeout"))
                .andExpect(jsonPath("$.items[1].issuanceMethod").value("seller_api"))
                .andExpect(jsonPath("$.items[1].quantity").value(2))
                .andExpect(jsonPath("$.items[1].refundedAt").value("2026-10-01T10:16:40.250Z"));
    }

    @Test
    void everyOrderHasExactlyTheFieldsOfTheSchemaAndNoneOfTheBuyer() throws Exception {
        String body = mvc.perform(as(seller(ANNA), "/api/v1/seller/orders")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<java.util.Map<String, Object>> items = JsonPath.read(body, "$.items");
        for (java.util.Map<String, Object> item : items) {
            assertEquals(FIELDS, item.keySet());
        }
        for (String forbidden : List.of("buyerId", "buyer", "email", "address", "delivery", "deliveryAddress", "reserveExpiresAt", "paymentSessionExpiresAt")) {
            assertFalse(body.contains("\"" + forbidden + "\""), forbidden);
        }
        assertFalse(body.contains("buyer@mail.example"));
    }

    @Test
    void sellerComesFromTheIdentityNotFromTheRequest() throws Exception {
        mvc.perform(as(seller(IVAN), "/api/v1/seller/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value(id(4).toString()));
        mvc.perform(as(seller(IVAN), "/api/v1/seller/orders?sellerId=" + ANNA))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_value"));
        assertEquals(IVAN, orders.lastSellerFilter().sellerId());
    }

    @Test
    void anApiKeyIdentityReadsTheOrdersOfItsOwnProfile() throws Exception {
        mvc.perform(as(AuthenticatedUser.forApiKey(IVAN.toString()), "/api/v1/seller/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value(id(4).toString()));
        assertEquals(IVAN, orders.lastSellerFilter().sellerId());
    }

    @Test
    void aTokenWithoutTheSellerProfileIsForbidden() throws Exception {
        AuthenticatedUser noProfile = new AuthenticatedUser(UUID.randomUUID().toString(), Set.of("seller.catalog"), Set.of("seller"), List.of("pwd", "otp"),
                true);
        mvc.perform(as(noProfile, "/api/v1/seller/orders"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void noVerifiedUserMeansUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/seller/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void statusAndPeriodFiltersReachTheService() throws Exception {
        mvc.perform(as(seller(ANNA), "/api/v1/seller/orders?status=issued,refunded&createdFrom=2026-10-01T10:00:00Z&createdTo=2026-10-02T00:00:00Z&limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        var filter = orders.lastSellerFilter();
        assertEquals(Optional.of(List.of("issued", "refunded")), filter.statuses());
        assertEquals(Optional.of(Instant.parse("2026-10-01T10:00:00Z")), filter.createdFrom());
        assertEquals(Optional.of(Instant.parse("2026-10-02T00:00:00Z")), filter.createdTo());
        assertEquals(5, filter.limit());
    }

    @Test
    void unknownStatusAndDisorderedPeriodAndBadInstantAreRejected() throws Exception {
        mvc.perform(as(seller(ANNA), "/api/v1/seller/orders?status=lost"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].parameter").value("status"))
                .andExpect(jsonPath("$.errors[0].code").value("unknown_value"));
        mvc.perform(as(seller(ANNA), "/api/v1/seller/orders?createdFrom=2026-10-02T00:00:00Z&createdTo=2026-10-01T00:00:00Z"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("out_of_range"));
        mvc.perform(as(seller(ANNA), "/api/v1/seller/orders?createdFrom=вчера"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("invalid_format"));
    }

    @Test
    void cursorContinuesTheListAndAGarbageCursorIsRejected() throws Exception {
        MvcResult first = mvc.perform(as(seller(ANNA), "/api/v1/seller/orders?limit=2")).andExpect(status().isOk()).andReturn();
        String cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.page.nextCursor");
        assertNotNull(cursor);

        String secondBody = mvc.perform(as(seller(ANNA), "/api/v1/seller/orders?limit=2&cursor=" + cursor)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        List<String> ids = JsonPath.read(secondBody, "$.items[*].orderId");
        assertEquals(List.of(id(1).toString()), ids);
        Object next = JsonPath.read(secondBody, "$.page.nextCursor");
        assertNull(next);

        mvc.perform(as(seller(ANNA), "/api/v1/seller/orders?cursor=AAAA"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].parameter").value("cursor"));
    }
}
