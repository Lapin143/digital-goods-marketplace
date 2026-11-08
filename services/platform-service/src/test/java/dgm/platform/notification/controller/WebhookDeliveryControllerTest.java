package dgm.platform.notification.controller;

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
import dgm.platform.notification.domain.NewWebhookDelivery;
import dgm.platform.notification.service.WebhookDeliveryService;
import dgm.platform.notification.testing.InMemoryWebhookDeliveries;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * {@code listWebhookDeliveries} (TC-972, OP-102): продавец берётся из токена, чужие уведомления не видны, в строке ровно поля схемы {@code WebhookDelivery}
 * и нет тела, подписи и данных покупателя, параметры проверяются, ответ не кэшируется.
 */
@Tag("TC-972")
class WebhookDeliveryControllerTest {

    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00.250Z");
    private static final UUID ANNA = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID IVAN = UUID.fromString("0199e0a0-0000-7000-8000-000000000102");
    private static final String CANARY = "buyer-canary-4471@example.test";
    private static final Set<String> FIELDS = Set.of("deliveryId", "eventId", "type", "orderNumber", "status", "attempts", "lastResponseCode", "lastAttemptAt",
            "nextAttemptAt", "createdAt");

    private InMemoryWebhookDeliveries queue;
    private MockMvc mvc;

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", n));
    }

    private static AuthenticatedUser seller(UUID profile) {
        return new AuthenticatedUser(UUID.randomUUID().toString(), Set.of("seller.catalog"), Set.of("seller"), List.of("pwd", "otp"), true, profile.toString());
    }

    private static MockHttpServletRequestBuilder as(AuthenticatedUser user, String url) {
        return get(url).requestAttr(JwtFilter.USER_ATTRIBUTE, user);
    }

    private void enqueue(UUID seller, int n, String type) {
        // в теле лежит строка, похожая на данные покупателя: в ответ списка она попасть не должна
        queue.insert(new NewWebhookDelivery(id(n), seller, id(5000 + n), type, 1000L + n, Map.of("type", type, "note", CANARY), T0.plusSeconds(n)));
    }

    @BeforeEach
    void setUp() {
        queue = new InMemoryWebhookDeliveries();
        enqueue(ANNA, 1, "order.paid");
        enqueue(ANNA, 2, "order.issued");
        enqueue(ANNA, 3, "order.refunded");
        enqueue(IVAN, 4, "order.paid");
        queue.markSent(id(1), 1, 200, T0.plusSeconds(5));
        queue.reschedule(id(2), 2, T0.plusSeconds(100), 503, "seller_http_503", T0.plusSeconds(8));
        mvc = MockMvcBuilders.standaloneSetup(new WebhookDeliveryController(new WebhookDeliveryService(queue))).setControllerAdvice(new ProblemAdvice()).build();
    }

    @Test
    void ownNotificationsAreReturnedNewestFirstWithoutCaching() throws Exception {
        mvc.perform(as(seller(ANNA), "/api/v1/seller/webhook-deliveries"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].deliveryId").value(id(3).toString()))
                .andExpect(jsonPath("$.items[0].type").value("order.refunded"))
                .andExpect(jsonPath("$.items[0].orderNumber").value(1003))
                .andExpect(jsonPath("$.items[0].status").value("queued"))
                .andExpect(jsonPath("$.items[0].attempts").value(0))
                .andExpect(jsonPath("$.items[0].lastResponseCode").doesNotExist())
                .andExpect(jsonPath("$.items[0].lastAttemptAt").doesNotExist())
                .andExpect(jsonPath("$.items[0].nextAttemptAt").value("2026-11-09T10:00:03.250Z"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-09T10:00:03.250Z"))
                .andExpect(jsonPath("$.page.limit").value(20));
    }

    @Test
    void aFailedAttemptShowsTheCodeTheCountAndTheMomentOfTheNextOne() throws Exception {
        mvc.perform(as(seller(ANNA), "/api/v1/seller/webhook-deliveries"))
                .andExpect(jsonPath("$.items[1].deliveryId").value(id(2).toString()))
                .andExpect(jsonPath("$.items[1].eventId").value(id(5002).toString()))
                .andExpect(jsonPath("$.items[1].status").value("queued"))
                .andExpect(jsonPath("$.items[1].attempts").value(2))
                .andExpect(jsonPath("$.items[1].lastResponseCode").value(503))
                .andExpect(jsonPath("$.items[1].lastAttemptAt").value("2026-11-09T10:00:08.250Z"))
                .andExpect(jsonPath("$.items[1].nextAttemptAt").value("2026-11-09T10:01:40.250Z"))
                .andExpect(jsonPath("$.items[2].status").value("sent"))
                .andExpect(jsonPath("$.items[2].attempts").value(1))
                .andExpect(jsonPath("$.items[2].lastResponseCode").value(200))
                .andExpect(jsonPath("$.items[2].nextAttemptAt").doesNotExist());
    }

    @Test
    void everyRowHasExactlyTheFieldsOfTheSchemaAndNoBodyNoSignatureNoBuyer() throws Exception {
        String body = mvc.perform(as(seller(ANNA), "/api/v1/seller/webhook-deliveries")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        List<Map<String, Object>> items = JsonPath.read(body, "$.items");
        for (Map<String, Object> item : items) {
            assertEquals(FIELDS, item.keySet());
        }
        for (String forbidden : List.of("payload", "body", "signature", "secret", "buyerId", "email", "address", "delivery\"", "endpointUrl", "url")) {
            assertFalse(body.contains("\"" + forbidden), forbidden);
        }
        assertFalse(body.contains(CANARY), "канарейка из тела уведомления в список не попадает (INV-44)");
    }

    @Test
    void sellerComesFromTheTokenNotFromTheRequest() throws Exception {
        mvc.perform(as(seller(IVAN), "/api/v1/seller/webhook-deliveries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].deliveryId").value(id(4).toString()));
        mvc.perform(as(seller(IVAN), "/api/v1/seller/webhook-deliveries?sellerId=" + ANNA))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_value"));
    }

    @Test
    void aTokenWithoutTheSellerProfileIsForbiddenAndNoTokenIsUnauthenticated() throws Exception {
        AuthenticatedUser noProfile = new AuthenticatedUser(UUID.randomUUID().toString(), Set.of("seller.catalog"), Set.of("seller"), List.of("pwd", "otp"),
                true);
        mvc.perform(as(noProfile, "/api/v1/seller/webhook-deliveries"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mvc.perform(get("/api/v1/seller/webhook-deliveries"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void theFilterByStatusAndTheLimitReachTheList() throws Exception {
        mvc.perform(as(seller(ANNA), "/api/v1/seller/webhook-deliveries?status=sent,queued&limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page.limit").value(2));
        mvc.perform(as(seller(ANNA), "/api/v1/seller/webhook-deliveries?status=failed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist());
    }

    @Test
    void anUnknownStatusIsRejected() throws Exception {
        mvc.perform(as(seller(ANNA), "/api/v1/seller/webhook-deliveries?status=lost"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].parameter").value("status"))
                .andExpect(jsonPath("$.errors[0].code").value("unknown_value"));
    }

    @Test
    void cursorContinuesTheListAndAGarbageCursorIsRejected() throws Exception {
        MvcResult first = mvc.perform(as(seller(ANNA), "/api/v1/seller/webhook-deliveries?limit=2")).andExpect(status().isOk()).andReturn();
        String cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.page.nextCursor");
        assertNotNull(cursor);

        String secondBody = mvc.perform(as(seller(ANNA), "/api/v1/seller/webhook-deliveries?limit=2&cursor=" + cursor)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        List<String> ids = JsonPath.read(secondBody, "$.items[*].deliveryId");
        assertEquals(List.of(id(1).toString()), ids);
        Object next = JsonPath.read(secondBody, "$.page.nextCursor");
        assertNull(next);

        mvc.perform(as(seller(ANNA), "/api/v1/seller/webhook-deliveries?cursor=AAAA"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].parameter").value("cursor"));
    }
}
