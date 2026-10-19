package dgm.order.orders.controller;

import static dgm.order.orders.FakeOrders.id;
import static dgm.order.orders.FakeOrders.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemAdvice;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.kit.time.ManualClock;
import dgm.order.orders.FakeOrders;
import dgm.order.orders.client.CatalogClient.ProductCard;
import dgm.order.orders.client.InventoryClient;
import dgm.order.orders.client.InventoryClient.OutOfStock;
import dgm.order.orders.client.InventoryClient.Reserved;
import dgm.order.orders.client.PaymentClient.Opened;
import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderSession;
import dgm.order.orders.service.FakeOrderStore;
import dgm.order.orders.service.OrderSettings;
import dgm.order.orders.service.OrderTransitions;
import dgm.order.orders.service.OrderViewService;
import dgm.order.orders.service.SagaOrchestrator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code createOrder}, {@code getOrder} и {@code getOrderPaymentSession} через контроллер: владелец из токена, подтверждённый телефон (U2), снимок
 * e-mail из токена, тело и ответ по схемам, ошибки саги доходят до покупателя с нужными полями и заголовками.
 */
class OrderPurchaseControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-18T12:00:00.123Z");
    private static final UUID ALICE = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID BOB = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final String URL = "https://localhost:8443/payment/pay/pay_1";
    private static final String BODY = "{\"productId\":\"" + PRODUCT + "\",\"quantity\":2}";

    private final AtomicInteger catalogCalls = new AtomicInteger();
    private ReserveStep inventory;
    private MockMvc mvc;

    /** Шаг резерва, который тест подменяет по ходу: подтверждение резерва в этих сценариях не вызывается. */
    @FunctionalInterface
    private interface ReserveStep {
        InventoryClient.ReserveResult reserve(UUID orderId, UUID productId, int quantity);
    }

    private static AuthenticatedUser buyer(UUID id, boolean phone, String email, String... amr) {
        return new AuthenticatedUser(id.toString(), Set.of("orders.create", "orders.read"), Set.of("buyer"), List.of(amr), phone, null, email);
    }

    private static AuthenticatedUser alice() {
        return buyer(ALICE, true, "buyer@mail.example", "pwd");
    }

    @BeforeEach
    void setUp() {
        FakeOrderStore store = new FakeOrderStore();
        ManualClock clock = new ManualClock(T0);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OrderSettings settings = new OrderSettings(3, Duration.ofMinutes(15), Duration.ofMinutes(12), 200);
        inventory = (orderId, productId, quantity) -> new Reserved(UUID.randomUUID(), T0.plus(Duration.ofMinutes(15)));
        SagaOrchestrator saga = new SagaOrchestrator(productId -> {
            catalogCalls.incrementAndGet();
            return Optional.of(new ProductCard(PRODUCT, SELLER, "Лунная тропа: ключ Steam", "published", 149_900, "RUB", "key_pool"));
        }, new InventoryClient() {
            @Override
            public ReserveResult reserve(UUID orderId, UUID productId, int quantity) {
                return inventory.reserve(orderId, productId, quantity);
            }

            @Override
            public ConfirmResult confirm(UUID orderId, UUID productId, int quantity) {
                throw new UnsupportedOperationException("подтверждение резерва в этих сценариях не вызывается");
            }
        }, (orderId, amount, currency, end) -> new Opened(URL, end),
                new OrderTransitions(store, store, settings, meters), settings, clock, meters);

        Order awaiting = new Order(id(1), 1001L, PRODUCT, "Лунная тропа: ключ Steam", 2, 149_900, 299_800, "RUB", "awaiting_payment", Optional.empty(), "email",
                "buyer@mail.example", Optional.of(T0.plusSeconds(900)), Optional.of(T0.plusSeconds(720)), T0, Optional.empty(), Optional.empty());
        Order paid = new Order(id(2), 1002L, PRODUCT, "Игра", 1, 100_00, 100_00, "RUB", "paid", Optional.empty(), "email", "buyer@mail.example",
                Optional.empty(), Optional.empty(), T0, Optional.of(T0.plusSeconds(30)), Optional.empty());
        FakeOrders orders = new FakeOrders()
                .with(ALICE, awaiting, paid)
                .withSession(ALICE, new OrderSession(id(1), "awaiting_payment", Optional.empty(), Optional.of(URL), Optional.of(T0.plusSeconds(720))))
                .withSession(ALICE, new OrderSession(id(2), "paid", Optional.empty(), Optional.empty(), Optional.empty()))
                .with(BOB, order(3, "paid", T0));
        mvc = MockMvcBuilders.standaloneSetup(new OrderPurchaseController(saga, new OrderViewService(orders, clock)))
                .setControllerAdvice(new ProblemAdvice()).build();
    }

    private static String str(String json, String path) {
        String value = JsonPath.read(json, path);
        return value;
    }

    private static int num(String json, String path) {
        Number value = JsonPath.read(json, path);
        return value.intValue();
    }

    private static Object any(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value;
    }

    private MvcResult create(AuthenticatedUser user, String body) throws Exception {
        return mvc.perform(post("/api/v1/orders").requestAttr(JwtFilter.USER_ATTRIBUTE, user).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    // ------------------------------------------------------------------------------------------------ TC-511

    @Tag("TC-511")
    @Test
    void anOrderIsCreatedWith201TheLocationAndThePaymentSession() throws Exception {
        MvcResult result = create(alice(), BODY);

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        String json = result.getResponse().getContentAsString();
        String orderId = str(json, "$.orderId");
        assertEquals("/api/v1/orders/" + orderId, result.getResponse().getHeader("Location"));
        assertEquals(1001, num(json, "$.orderNumber"));
        assertEquals(PRODUCT.toString(), str(json, "$.productId"));
        assertEquals("Лунная тропа: ключ Steam", str(json, "$.productTitle"));
        assertEquals(2, num(json, "$.quantity"));
        assertEquals(149_900, num(json, "$.unitPrice.amount"));
        assertEquals("RUB", str(json, "$.unitPrice.currency"));
        assertEquals(299_800, num(json, "$.total.amount"));
        assertEquals("awaiting_payment", str(json, "$.status"));
        assertNull(any(json, "$.cancelReason"));
        assertEquals("email", str(json, "$.delivery.channel"));
        assertEquals("buyer@mail.example", str(json, "$.delivery.address"));
        assertEquals(Boolean.FALSE, any(json, "$.delivery.masked"));
        assertEquals("2026-10-18T12:15:00.123Z", str(json, "$.reserveExpiresAt"));
        assertEquals("2026-10-18T12:12:00.123Z", str(json, "$.paymentSessionExpiresAt"));
        assertEquals("2026-10-18T12:00:00.123Z", str(json, "$.createdAt"));
        assertNull(any(json, "$.paidAt"));
        assertNull(any(json, "$.issuedAt"));
        assertEquals(URL, str(json, "$.paymentSession.url"));
        assertEquals("2026-10-18T12:12:00.123Z", str(json, "$.paymentSession.expiresAt"));
    }

    @Tag("TC-511")
    @Test
    void theResponseHoldsExactlyTheFieldsOfTheSchemaAndNothingOfTheSellerOrTheCommission() throws Exception {
        String json = create(alice(), BODY).getResponse().getContentAsString();

        Map<String, Object> body = Json.parseObject(json);
        assertEquals(Set.of("orderId", "orderNumber", "productId", "productTitle", "quantity", "unitPrice", "total", "status", "cancelReason", "delivery",
                "reserveExpiresAt", "paymentSessionExpiresAt", "createdAt", "paidAt", "issuedAt", "paymentSession"), body.keySet());
        assertFalse(json.contains("commission"));
        assertFalse(json.contains(SELLER.toString()));
    }

    @Tag("TC-511")
    @Test
    void theBuyerIsTheOwnerFromTheTokenAndTheEmailIsTheSnapshotFromTheToken() throws Exception {
        MvcResult result = create(buyer(BOB, true, "bob@mail.example", "pwd"), BODY);

        assertEquals(201, result.getResponse().getStatus());
        assertEquals("bob@mail.example", str(result.getResponse().getContentAsString(), "$.delivery.address"));
    }

    @Tag("TC-511")
    @Test
    void theSagaProblemsReachTheBuyerWithTheirFieldsAndHeaders() throws Exception {
        inventory = (orderId, productId, quantity) -> new OutOfStock(1);
        mvc.perform(post("/api/v1/orders").requestAttr(JwtFilter.USER_ATTRIBUTE, alice()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("insufficient-stock"))
                .andExpect(jsonPath("$.available").value(1));

        inventory = (orderId, productId, quantity) -> {
            throw new DependencyUnavailableException("inventory-service", DependencyUnavailableException.TIMEOUT, 0, null);
        };
        mvc.perform(post("/api/v1/orders").requestAttr(JwtFilter.USER_ATTRIBUTE, alice()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("dependency-unavailable"));
    }

    // ------------------------------------------------------------------------------------------------ TC-512

    @Tag("TC-512")
    @Test
    void theFourthUnpaidOrderGets409WithTheLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(201, create(alice(), BODY).getResponse().getStatus());
        }

        mvc.perform(post("/api/v1/orders").requestAttr(JwtFilter.USER_ATTRIBUTE, alice()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("unpaid-orders-limit"))
                .andExpect(jsonPath("$.limit").value(3));
    }

    // ------------------------------------------------------------------------------------------------ TC-515

    @Tag("TC-515")
    @Test
    void aBuyerWithoutAConfirmedPhoneGets403BeforeAnythingElseHappens() throws Exception {
        mvc.perform(post("/api/v1/orders").requestAttr(JwtFilter.USER_ATTRIBUTE, buyer(ALICE, false, "buyer@mail.example", "pwd"))
                        .contentType(MediaType.APPLICATION_JSON).content("not even json"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("phone-not-confirmed"));
        assertEquals(0, catalogCalls.get(), "каталог не вызывался");
    }

    @Tag("TC-515")
    @Test
    void aTokenWithoutAnEmailGets422BecauseThereIsNowhereToDeliver() throws Exception {
        mvc.perform(post("/api/v1/orders").requestAttr(JwtFilter.USER_ATTRIBUTE, buyer(ALICE, true, null, "pwd"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("validation-failed"));
        assertEquals(0, catalogCalls.get());
    }

    @Tag("TC-515")
    @Test
    void anInvalidBodyGets422WithTheListOfErrorsAndNoNeighbourIsCalled() throws Exception {
        mvc.perform(post("/api/v1/orders").requestAttr(JwtFilter.USER_ATTRIBUTE, alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + PRODUCT + "\",\"quantity\":11,\"price\":1}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].pointer").value("/price"))
                .andExpect(jsonPath("$.errors[1].pointer").value("/quantity"));
        assertEquals(0, catalogCalls.get());
    }

    @Tag("TC-515")
    @Test
    void aBodyThatIsNotJsonGets400AndAnotherContentTypeGets415() throws Exception {
        mvc.perform(post("/api/v1/orders").requestAttr(JwtFilter.USER_ATTRIBUTE, alice()).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad-request"));
        mvc.perform(post("/api/v1/orders").requestAttr(JwtFilter.USER_ATTRIBUTE, alice()).contentType(MediaType.TEXT_PLAIN).content(BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported-media-type"));
    }

    @Tag("TC-515")
    @Test
    void noVerifiedUserOrASubjectThatIsNotAUuidMeansUnauthenticated() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        AuthenticatedUser odd = new AuthenticatedUser("alice", Set.of("orders.create"), Set.of("buyer"), List.of("pwd"), true, null, "a@b.example");
        mvc.perform(post("/api/v1/orders").requestAttr(JwtFilter.USER_ATTRIBUTE, odd).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders/" + id(1))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders/" + id(1) + "/payment-session")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------------------------ TC-514

    @Tag("TC-514")
    @Test
    void theOwnerGetsTheOrderWithoutCachingAndWithoutTheSession() throws Exception {
        mvc.perform(get("/api/v1/orders/" + id(1)).requestAttr(JwtFilter.USER_ATTRIBUTE, alice()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.orderId").value(id(1).toString()))
                .andExpect(jsonPath("$.status").value("awaiting_payment"))
                .andExpect(jsonPath("$.orderNumber").value(1001))
                .andExpect(jsonPath("$.delivery.address").value("buyer@mail.example"))
                .andExpect(jsonPath("$.delivery.masked").value(false))
                .andExpect(jsonPath("$.paymentSession").doesNotExist());
    }

    @Tag("TC-514")
    @Test
    void theAddressIsMaskedInAnSmsSession() throws Exception {
        mvc.perform(get("/api/v1/orders/" + id(1)).requestAttr(JwtFilter.USER_ATTRIBUTE, buyer(ALICE, true, "buyer@mail.example", "sms")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.address").value("b***@mail.example"))
                .andExpect(jsonPath("$.delivery.masked").value(true));
    }

    @Tag("TC-514")
    @Test
    void someoneElsesOrderAndAnUnknownOneGet404() throws Exception {
        mvc.perform(get("/api/v1/orders/" + id(3)).requestAttr(JwtFilter.USER_ATTRIBUTE, alice()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
        mvc.perform(get("/api/v1/orders/" + id(99)).requestAttr(JwtFilter.USER_ATTRIBUTE, alice()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/" + id(1) + "/payment-session").requestAttr(JwtFilter.USER_ATTRIBUTE, buyer(BOB, true, "bob@mail.example", "pwd")))
                .andExpect(status().isNotFound());
    }

    @Tag("TC-514")
    @Test
    void anOrderIdThatIsNotALowercaseUuidGets400() throws Exception {
        for (String bad : new String[] {"abc", PRODUCT.toString().toUpperCase(), "1", "0199e0a0-0000-7000-8000-00000000030"}) {
            mvc.perform(get("/api/v1/orders/" + bad).requestAttr(JwtFilter.USER_ATTRIBUTE, alice())).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/orders/" + bad + "/payment-session").requestAttr(JwtFilter.USER_ATTRIBUTE, alice())).andExpect(status().isBadRequest());
        }
    }

    @Tag("TC-514")
    @Test
    void thePaymentSessionOfAnOrderAwaitingPaymentIsReturned() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/orders/" + id(1) + "/payment-session").requestAttr(JwtFilter.USER_ATTRIBUTE, alice()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.orderId").value(id(1).toString()))
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-18T12:12:00.123Z"))
                .andReturn();
        assertNotNull(result);
        assertEquals(3, Json.parseObject(result.getResponse().getContentAsString()).size());
    }

    @Tag("TC-514")
    @Test
    void thePaymentSessionOfAPaidOrderGets409WithTheStatusAndNoLink() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/orders/" + id(2) + "/payment-session").requestAttr(JwtFilter.USER_ATTRIBUTE, alice()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("state-conflict"))
                .andExpect(jsonPath("$.currentStatus").value("paid"))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(URL));
    }
}
