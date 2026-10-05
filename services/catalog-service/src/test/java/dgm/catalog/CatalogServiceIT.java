package dgm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.ReadinessProbe;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.route.RoutePolicy;
import dgm.kit.route.RouteRule;
import dgm.kit.testing.TestTokens;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Сервис каталога целиком на стенде (make up SET=dev-min DEBUG=1): приложение стартует в процессе теста, применяет миграции под ролью
 * мигратора вместе с тестовыми данными (профиль testdata), читает базу по TLS под рабочей ролью и отвечает по mTLS. Проверяются две
 * операции каталога, защита маршрутов и служебные порты.
 */
@Tag("integration")
@SpringBootTest(classes = CatalogServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("testdata")
@Import(CatalogServiceIT.TestBeans.class)
class CatalogServiceIT {

    private static final int PORT = StandHttp.freePort();
    private static final int MANAGEMENT = StandHttp.freePort();
    private static final TestTokens TOKENS = TestTokens.create(Clock.systemUTC());

    private static final String P201 = "0199e0a0-0000-7000-8000-000000000201";
    private static final String P202 = "0199e0a0-0000-7000-8000-000000000202";
    private static final String P203 = "0199e0a0-0000-7000-8000-000000000203";
    private static final String P204 = "0199e0a0-0000-7000-8000-000000000204";
    private static final String P205 = "0199e0a0-0000-7000-8000-000000000205";
    private static final String P206_DRAFT = "0199e0a0-0000-7000-8000-000000000206";
    private static final String P207_BLOCKED = "0199e0a0-0000-7000-8000-000000000207";

    private static StandHttp gateway;
    private static StandHttp orderService;
    private static StandHttp anonymous;
    private static StandHttp monitoring;

    @Autowired
    private ReadinessProbe probe;

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
        orderService = StandHttp.asService("order-service", PORT);
        anonymous = StandHttp.withoutCertificate(PORT);
        monitoring = StandHttp.asService("prometheus", MANAGEMENT);
    }

    private static List<String> ids(Response response) {
        List<?> items = (List<?>) response.json().get("items");
        List<String> ids = new ArrayList<>();
        for (Object item : items) {
            ids.add((String) ((Map<?, ?>) item).get("productId"));
        }
        return ids;
    }

    private static String code(Response response) {
        return (String) response.json().get("code");
    }

    // ---------------------------------------------------------------- listStorefrontProducts

    @Test
    void storefrontListsPublishedProductsNewestFirstWithoutToken() throws IOException {
        Response response = gateway.get("/api/v1/products");
        assertEquals(200, response.status());
        assertEquals(List.of(P201, P202, P203, P204, P205), ids(response));
        assertEquals("public, max-age=30", response.header("Cache-Control"));
        assertNotNull(response.header("ETag"));
        assertTrue(response.header("Content-Type").startsWith("application/json"));
    }

    @Test
    void draftAndBlockedProductsAreNotShown() throws IOException {
        List<String> shown = ids(gateway.get("/api/v1/products?limit=100"));
        assertFalse(shown.contains(P206_DRAFT));
        assertFalse(shown.contains(P207_BLOCKED));
    }

    @Test
    void stockFlagComesFromTheStockCopy() throws IOException {
        List<?> items = (List<?>) gateway.get("/api/v1/products").json().get("items");
        Map<String, Boolean> inStock = new java.util.LinkedHashMap<>();
        for (Object item : items) {
            Map<?, ?> row = (Map<?, ?>) item;
            inStock.put((String) row.get("productId"), (Boolean) row.get("inStock"));
        }
        assertEquals(true, inStock.get(P201));
        assertEquals(false, inStock.get(P202), "остаток 0");
        assertEquals(true, inStock.get(P203));
        assertEquals(false, inStock.get(P205), "строки остатка нет: товар показывается как «нет в наличии»");
    }

    @Test
    void sameDataGivesNotModifiedForConditionalRequest() throws IOException {
        Response first = gateway.get("/api/v1/products");
        Response second = gateway.get("/api/v1/products", "If-None-Match", first.header("ETag"));
        assertEquals(304, second.status());
        assertEquals(first.header("ETag"), second.header("ETag"));
        assertEquals("", second.body());
    }

    @Test
    void searchByTitleIsCaseInsensitive() throws IOException {
        assertEquals(List.of(P201, P202), ids(gateway.get("/api/v1/products?q=" + encode("STEAM"))));
    }

    @Test
    void filterByProductType() throws IOException {
        assertEquals(List.of(P204), ids(gateway.get("/api/v1/products?productType=gift_card")));
    }

    @Test
    void keysetPagingWalksTheWholeShowcaseOnce() throws IOException {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Response page = gateway.get("/api/v1/products?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            assertEquals(200, page.status());
            seen.addAll(ids(page));
            cursor = (String) ((Map<?, ?>) page.json().get("page")).get("nextCursor");
            pages++;
        } while (cursor != null && pages < 10);
        assertEquals(3, pages);
        assertEquals(List.of(P201, P202, P203, P204, P205), seen);
    }

    @Test
    void invalidParametersAreRejectedWith422() throws IOException {
        Response reversed = gateway.get("/api/v1/products?priceFrom=500000&priceTo=100");
        assertEquals(422, reversed.status());
        assertEquals("validation-failed", code(reversed));
        assertTrue(reversed.header("Content-Type").startsWith("application/problem+json"));

        Response unknown = gateway.get("/api/v1/products?color=red");
        assertEquals(422, unknown.status());
    }

    // ---------------------------------------------------------------- getProductCard

    @Test
    void orderServiceGetsProductCardInAnyStatus() throws IOException {
        Response published = orderService.get("/internal/v1/products/" + P201);
        assertEquals(200, published.status());
        assertEquals("published", published.json().get("status"));
        assertEquals("no-store", published.header("Cache-Control"));
        Map<?, ?> price = (Map<?, ?>) published.json().get("price");
        assertEquals(149_900, ((Number) price.get("amount")).intValue());
        assertEquals("RUB", price.get("currency"));

        Response blocked = orderService.get("/internal/v1/products/" + P207_BLOCKED);
        assertEquals(200, blocked.status());
        assertEquals("blocked", blocked.json().get("status"));
    }

    @Test
    void otherServicesAreNotAllowedToCallInternalRoute() throws IOException {
        Response response = gateway.get("/internal/v1/products/" + P201);
        assertEquals(403, response.status());
        assertEquals("forbidden", code(response));
    }

    @Test
    void unknownAndMalformedProductIds() throws IOException {
        assertEquals(404, orderService.get("/internal/v1/products/0199e0a0-0000-7000-8000-000000000999").status());
        assertEquals(400, orderService.get("/internal/v1/products/not-a-uuid").status());
    }

    @Test
    void connectionWithoutClientCertificateIsRefused() {
        assertThrows(IOException.class, () -> anonymous.get("/api/v1/products"));
    }

    // ---------------------------------------------------------------- защита маршрутов

    @Test
    void routeAbsentFromContractIsNotFound() throws IOException {
        Response response = gateway.get("/api/v1/does-not-exist");
        assertEquals(404, response.status());
        assertEquals("not-found", code(response));
    }

    @Test
    void protectedRouteNeedsToken() throws IOException {
        RouteRule protectedGet = RoutePolicy.fromClasspath("dgm/routes.json").rules().stream()
                .filter(r -> r.method().equals("GET") && r.requiresToken() && !r.path().contains("{")).findFirst().orElseThrow();
        Response noToken = gateway.get(protectedGet.path());
        assertEquals(401, noToken.status());
        assertEquals("unauthenticated", code(noToken));

        String wrongScope = TOKENS.token().scopes("nothing.here").build();
        assertEquals(403, gateway.get(protectedGet.path(), StandHttp.bearer(wrongScope)).status());

        String unsigned = TOKENS.token().buildUnsigned();
        assertEquals(401, gateway.get(protectedGet.path(), StandHttp.bearer(unsigned)).status());
    }

    // ---------------------------------------------------------------- служебные порты

    @Test
    void readinessOnManagementPortNeedsClientCertificate() throws IOException {
        Response health = monitoring.get("/actuator/health/readiness");
        assertEquals(200, health.status());
        assertEquals("UP", health.json().get("status"));
        assertThrows(IOException.class, () -> StandHttp.withoutCertificate(MANAGEMENT).get("/actuator/health/readiness"));
    }

    @Test
    void metricsAreExposedWithApplicationTag() throws IOException {
        Response metrics = monitoring.get("/actuator/prometheus");
        assertEquals(200, metrics.status());
        assertTrue(metrics.body().contains("application=\"catalog-service\""), "метка application");
    }

    @Test
    void outboxRelayBecomesLeaderWithDedicatedConnection() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        String leaderLine = "";
        while (System.nanoTime() < deadline) {
            leaderLine = monitoring.get("/actuator/prometheus").body().lines().filter(l -> l.startsWith("outbox_leader")).findFirst().orElse("");
            if (leaderLine.endsWith(" 1.0")) {
                break;
            }
            Thread.sleep(200);
        }
        assertTrue(leaderLine.endsWith(" 1.0"), "публикатор Outbox не стал активным: «" + leaderLine + "»");
    }

    @Test
    void probeForDockerAnswersOnLoopback() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + probe.port() + "/ready")).timeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("ready", response.body());
    }

    private static String encode(String text) {
        return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
    }
}
