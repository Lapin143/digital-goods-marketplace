package dgm.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.KitProperties;
import dgm.kit.boot.ModuleDatabases;
import dgm.kit.boot.SecretsDirectory;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.testing.TestTokens;
import dgm.kit.tls.TlsMaterial;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Сервис заказов целиком на стенде (make up SET=dev-min DEBUG=1): миграции с тестовыми данными, чтение базы под ролью
 * {@code app_orders}, токен с областью и ролью, ограничение по владельцу (U1), маскирование адреса в сессии по SMS.
 */
@Tag("integration")
@SpringBootTest(classes = OrderServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("testdata")
@Import(OrderServiceIT.TestBeans.class)
class OrderServiceIT {

    private static final int PORT = StandHttp.freePort();
    private static final int MANAGEMENT = StandHttp.freePort();
    private static final TestTokens TOKENS = TestTokens.create(Clock.systemUTC());

    private static final String BUYER_A = "0199e0a0-0000-4000-8000-0000000000a1";
    private static final String BUYER_B = "0199e0a0-0000-4000-8000-0000000000a2";
    private static final String O301 = "0199e0a0-0000-7000-8000-000000000301";
    private static final String O302 = "0199e0a0-0000-7000-8000-000000000302";
    private static final String O303 = "0199e0a0-0000-7000-8000-000000000303";
    private static final String O304 = "0199e0a0-0000-7000-8000-000000000304";

    private static StandHttp gateway;
    private static StandHttp monitoring;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        JwtDecoder jwtDecoder() {
            return StandHttp.decoderFor(TOKENS);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        StandHttp.register(registry, PORT, MANAGEMENT);
    }

    @BeforeAll
    static void clients() {
        gateway = StandHttp.asService("api-gateway", PORT);
        monitoring = StandHttp.asService("prometheus", MANAGEMENT);
    }

    private static String buyer(String id) {
        return TOKENS.token().subject(id).scopes("orders.read").roles("buyer").amr("pwd").build();
    }

    private static List<String> orderIds(Response response) {
        List<?> items = (List<?>) response.json().get("items");
        List<String> ids = new ArrayList<>();
        for (Object item : items) {
            ids.add((String) ((Map<?, ?>) item).get("orderId"));
        }
        return ids;
    }

    private static Map<?, ?> first(Response response) {
        return (Map<?, ?>) ((List<?>) response.json().get("items")).get(0);
    }

    @Autowired
    private KitProperties properties;
    @Autowired
    private SecretsDirectory secrets;
    @Autowired
    private TlsMaterial tls;

    /**
     * Тестовые данные (миграция 1000) уже в базе, сервис стартует без профиля testdata, как из IDE поверх наполненного стенда:
     * проверка миграций не должна падать на «применённой миграции, которой нет локально».
     */
    @Test
    void startWithoutTestdataOverFilledDatabaseValidates() {
        KitProperties.Db db = properties.db();
        KitProperties.Db plain = new KitProperties.Db(db.host(), db.port(), db.database(), db.migratorRole(), true,
                List.of("classpath:db/migration"), Map.of());
        try (ModuleDatabases opened = ModuleDatabases.open(plain, secrets, tls, properties.service())) {
            assertTrue(opened.modules().isEmpty());
        }
    }

    @Test
    void buyerSeesOnlyOwnOrdersNewestFirst() throws IOException {
        Response response = gateway.get("/api/v1/orders", StandHttp.bearer(buyer(BUYER_A)));
        assertEquals(200, response.status());
        assertEquals(List.of(O302, O301, O303), orderIds(response));
        assertEquals("no-store", response.header("Cache-Control"));
    }

    @Test
    void otherBuyerOrdersAreInvisible() throws IOException {
        Response b = gateway.get("/api/v1/orders", StandHttp.bearer(buyer(BUYER_B)));
        assertEquals(List.of(O304), orderIds(b));
        Response stranger = gateway.get("/api/v1/orders", StandHttp.bearer(buyer("0199e0a0-0000-4000-8000-0000000000ff")));
        assertEquals(List.of(), orderIds(stranger));
    }

    @Test
    void orderFieldsFollowTheContract() throws IOException {
        Response response = gateway.get("/api/v1/orders?status=issued", StandHttp.bearer(buyer(BUYER_A)));
        Map<?, ?> order = first(response);
        assertEquals(O301, order.get("orderId"));
        assertEquals("issued", order.get("status"));
        assertEquals(2, ((Number) order.get("quantity")).intValue());
        assertEquals(149_900, ((Number) ((Map<?, ?>) order.get("unitPrice")).get("amount")).intValue());
        assertEquals(299_800, ((Number) ((Map<?, ?>) order.get("total")).get("amount")).intValue());
        assertEquals("2026-10-03T12:00:00.123Z", order.get("createdAt"));
        assertEquals("2026-10-03T12:06:00.123Z", order.get("issuedAt"));
        assertNull(order.get("cancelReason"));
        Map<?, ?> delivery = (Map<?, ?>) order.get("delivery");
        assertEquals("buyer@mail.example", delivery.get("address"));
        assertEquals(false, delivery.get("masked"));
    }

    @Test
    void cancelledOrderCarriesReason() throws IOException {
        Response response = gateway.get("/api/v1/orders?status=cancelled", StandHttp.bearer(buyer(BUYER_A)));
        assertEquals(List.of(O303), orderIds(response));
        assertEquals("payment_declined", first(response).get("cancelReason"));
    }

    @Test
    void statusFilterAndPeriod() throws IOException {
        assertEquals(List.of(O302, O301), orderIds(gateway.get("/api/v1/orders?status=awaiting_payment,issued", StandHttp.bearer(buyer(BUYER_A)))));
        assertEquals(List.of(O303), orderIds(gateway.get("/api/v1/orders?createdTo=2026-10-03T00:00:00Z", StandHttp.bearer(buyer(BUYER_A)))));
        assertEquals(List.of(O302), orderIds(gateway.get("/api/v1/orders?createdFrom=2026-10-03T12:30:00Z", StandHttp.bearer(buyer(BUYER_A)))));
    }

    @Test
    void keysetPagingWalksTheHistoryOnce() throws IOException {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Response page = gateway.get("/api/v1/orders?limit=2" + (cursor == null ? "" : "&cursor=" + cursor), StandHttp.bearer(buyer(BUYER_A)));
            assertEquals(200, page.status());
            seen.addAll(orderIds(page));
            cursor = (String) ((Map<?, ?>) page.json().get("page")).get("nextCursor");
            pages++;
        } while (cursor != null && pages < 10);
        assertEquals(2, pages);
        assertEquals(List.of(O302, O301, O303), seen);
    }

    @Test
    void smsSessionSeesMaskedAddress() throws IOException {
        String sms = TOKENS.token().subject(BUYER_A).scopes("orders.read").roles("buyer").amr("sms").build();
        Response response = gateway.get("/api/v1/orders?status=issued", StandHttp.bearer(sms));
        assertEquals(200, response.status());
        Map<?, ?> delivery = (Map<?, ?>) first(response).get("delivery");
        assertEquals("b***@mail.example", delivery.get("address"));
        assertEquals(true, delivery.get("masked"));
    }

    @Test
    void requestWithoutTokenIs401() throws IOException {
        Response response = gateway.get("/api/v1/orders");
        assertEquals(401, response.status());
        assertEquals("unauthenticated", response.json().get("code"));
        assertTrue(response.header("Content-Type").startsWith("application/problem+json"));
        assertEquals("Bearer", response.header("WWW-Authenticate"));
    }

    @Test
    void invalidTokensAre401() throws IOException {
        String expired = TOKENS.token().subject(BUYER_A).expiresIn(Duration.ofMinutes(-10)).build();
        String foreignIssuer = TOKENS.token().subject(BUYER_A).issuer("https://evil.example/realms/dgm").build();
        String foreignAudience = TOKENS.token().subject(BUYER_A).audience("other-api").build();
        String foreignKey = TOKENS.token().subject(BUYER_A).signedWith(TestTokens.newPair()).build();
        String unsigned = TOKENS.token().subject(BUYER_A).buildUnsigned();
        for (String token : List.of(expired, foreignIssuer, foreignAudience, foreignKey, unsigned)) {
            assertEquals(401, gateway.get("/api/v1/orders", StandHttp.bearer(token)).status());
        }
    }

    @Test
    void missingScopeOrRoleIs403() throws IOException {
        String noScope = TOKENS.token().subject(BUYER_A).scopes("catalog.read").roles("buyer").build();
        Response forbidden = gateway.get("/api/v1/orders", StandHttp.bearer(noScope));
        assertEquals(403, forbidden.status());
        assertEquals("forbidden", forbidden.json().get("code"));

        String wrongRole = TOKENS.token().subject(BUYER_A).scopes("orders.read").roles("seller").build();
        assertEquals(403, gateway.get("/api/v1/orders", StandHttp.bearer(wrongRole)).status());
    }

    @Test
    void invalidParametersAre422() throws IOException {
        for (String query : List.of("status=lost", "limit=0", "limit=101", "createdFrom=вчера", "buyerId=" + BUYER_B, "cursor=AAAA")) {
            Response response = gateway.get("/api/v1/orders?" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8).replace("%3D", "="),
                    StandHttp.bearer(buyer(BUYER_A)));
            assertEquals(422, response.status(), query);
            assertEquals("validation-failed", response.json().get("code"), query);
        }
    }

    @Test
    void routeNotInContractIsNotFound() throws IOException {
        Response response = gateway.get("/api/v1/refunds", StandHttp.bearer(buyer(BUYER_A)));
        assertEquals(404, response.status());
        assertEquals("not-found", response.json().get("code"));
    }

    @Test
    void connectionWithoutClientCertificateIsRefused() {
        assertThrows(IOException.class, () -> StandHttp.withoutCertificate(PORT).get("/api/v1/orders"));
    }

    @Test
    void readinessAndMetricsOnManagementPort() throws IOException {
        assertEquals(200, monitoring.get("/actuator/health/readiness").status());
        String metrics = monitoring.get("/actuator/prometheus").body();
        assertTrue(metrics.contains("application=\"order-service\""));
    }
}
