package dgm.order.orders.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemAdvice;
import dgm.kit.time.ManualClock;
import dgm.order.orders.domain.OrderStatus;
import dgm.order.orders.domain.StoredOrder;
import dgm.order.orders.service.DeliveryAddressService;
import dgm.order.orders.service.FakeOrderStore;
import dgm.order.orders.service.OrderSettings;
import dgm.order.orders.service.OrderTransitions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code updateDeliveryAddress} (OP-72) через контроллер: тело и ответ по схемам, ошибки с нужными полями, адрес в ответах об ошибках не повторяется.
 * Охранник ключа идемпотентности и проверку вызывающего по сертификату делает каркас (фильтры), их проверяют тесты каркаса и стенд.
 */
class InternalOrderControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-18T12:00:00.123Z");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final UUID ISSUED = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PAID = UUID.fromString("0199e0a0-0000-7000-8000-000000000302");
    private static final UUID UNKNOWN = UUID.fromString("0199e0a0-0000-7000-8000-000000000399");
    private static final String TICKET = "0199e0a0-0000-7000-8000-000000000501";
    private static final String BODY = "{\"ticketId\":\"" + TICKET + "\",\"email\":\"canary.new@mail.example\"}";

    private FakeOrderStore store;
    private MockMvc mvc;

    private static StoredOrder order(UUID id, long number, String status) {
        return new StoredOrder(id, number, BUYER, SELLER, PRODUCT, "Игра", 1, 100_00, 100_00, 2_00, "RUB", status, null, "buyer@mail.example", null, null,
                null, T0, T0, OrderStatus.ISSUED.equals(status) ? T0 : null);
    }

    @BeforeEach
    void setUp() {
        store = new FakeOrderStore();
        store.seed(order(ISSUED, 1001, OrderStatus.ISSUED)).seed(order(PAID, 1002, OrderStatus.PAID));
        OrderTransitions transitions = new OrderTransitions(store, store, new OrderSettings(3, Duration.ofMinutes(15), Duration.ofMinutes(12), 200),
                new SimpleMeterRegistry());
        mvc = MockMvcBuilders.standaloneSetup(new InternalOrderController(new DeliveryAddressService(transitions, new ManualClock(T0))))
                .setControllerAdvice(new ProblemAdvice()).build();
    }

    private MvcResult call(String orderId, String body) throws Exception {
        return mvc.perform(post("/internal/v1/orders/" + orderId + "/update-delivery-address").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    @Tag("TC-528")
    @Test
    void theAddressIsReplacedAndTheAnswerFollowsTheSchema() throws Exception {
        MvcResult result = call(ISSUED.toString(), BODY);

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        Map<String, Object> body = Json.parseObject(result.getResponse().getContentAsString());
        assertEquals(Set.of("orderId", "delivery", "updatedAt"), body.keySet());
        assertEquals(ISSUED.toString(), body.get("orderId"));
        assertEquals(Map.of("channel", "email", "address", "canary.new@mail.example"), body.get("delivery"));
        assertEquals("2026-10-18T12:00:00.123Z", body.get("updatedAt"));
        assertEquals("canary.new@mail.example", store.get(ISSUED).deliveryAddress());
        assertEquals(1, store.events("order.address-updated").size());
    }

    @Tag("TC-528")
    @Test
    void anOrderThatIsNotIssuedIs409WithItsStatusAndWithoutTheAddress() throws Exception {
        mvc.perform(post("/internal/v1/orders/" + PAID + "/update-delivery-address").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("state-conflict"))
                .andExpect(jsonPath("$.currentStatus").value("paid"));

        MvcResult result = call(PAID.toString(), BODY);
        assertFalse(result.getResponse().getContentAsString().contains("canary"));
        assertEquals("buyer@mail.example", store.get(PAID).deliveryAddress());
        assertEquals(0, store.events().size());
    }

    @Tag("TC-528")
    @Test
    void anUnknownOrderIs404WithoutTheAddress() throws Exception {
        MvcResult result = call(UNKNOWN.toString(), BODY);

        assertEquals(404, result.getResponse().getStatus());
        assertEquals("not-found", Json.parseObject(result.getResponse().getContentAsString()).get("code"));
        assertFalse(result.getResponse().getContentAsString().contains("canary"));
        assertEquals(0, store.events().size());
    }

    @Tag("TC-529")
    @Test
    void anOrderIdThatIsNotALowercaseUuidIs400() throws Exception {
        for (String bad : new String[] {"not-a-uuid", ISSUED.toString().toUpperCase(), "123"}) {
            MvcResult result = call(bad, BODY);
            assertEquals(400, result.getResponse().getStatus(), bad);
            assertEquals("bad-request", Json.parseObject(result.getResponse().getContentAsString()).get("code"), bad);
        }
        assertEquals(0, store.events().size());
    }

    @Tag("TC-529")
    @Test
    void aBadBodyIs422WithTheListOfErrorsAndWithoutTheAddress() throws Exception {
        String body = "{\"ticketId\":\"x\",\"email\":\"canary-not-an-address\",\"extra\":1}";
        mvc.perform(post("/internal/v1/orders/" + ISSUED + "/update-delivery-address").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].pointer").value("/extra"))
                .andExpect(jsonPath("$.errors[1].pointer").value("/ticketId"))
                .andExpect(jsonPath("$.errors[2].pointer").value("/email"));

        assertFalse(call(ISSUED.toString(), body).getResponse().getContentAsString().contains("canary"));
        assertEquals("buyer@mail.example", store.get(ISSUED).deliveryAddress());
    }

    @Tag("TC-529")
    @Test
    void anotherContentTypeIs415AndABodyThatIsNotJsonIs400() throws Exception {
        mvc.perform(post("/internal/v1/orders/" + ISSUED + "/update-delivery-address").contentType(MediaType.TEXT_PLAIN).content(BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported-media-type"));
        mvc.perform(post("/internal/v1/orders/" + ISSUED + "/update-delivery-address").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("json")));
        assertEquals(0, store.events().size());
    }
}
