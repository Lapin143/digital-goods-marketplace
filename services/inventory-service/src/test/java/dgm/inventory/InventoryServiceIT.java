package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.testing.TestTokens;
import java.io.IOException;
import java.time.Clock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Сервис инвентаря целиком на стенде (набор dev-kit с отладочными портами): запуск с миграциями под ролью-мигратором, проверка токена и
 * вызывающего по сертификату, правила маршрутов из OpenAPI, порт управления. Операции пула ключей появятся в шаге 14 Ф4 (там же растёт
 * этот класс); пока он держит задание {@code kit} честным: сервис собирается, запускается и защищён так же, как остальные.
 * Ответы складываются в build/contract-samples и проверяются {@code check_contract.py}.
 */
@Tag("integration")
@SpringBootTest(classes = InventoryServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(InventoryServiceIT.TestBeans.class)
class InventoryServiceIT {

    private static final int PORT = StandHttp.freePort();
    private static final int MANAGEMENT = StandHttp.freePort();
    private static final TestTokens TOKENS = TestTokens.create(Clock.systemUTC());

    private static final String SELLER = "0199e0a0-0000-4000-8000-0000000000b1";
    private static final String PRODUCT = "0199e0a0-0000-7000-8000-000000000201";

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

    private static String stockPath() {
        return "/api/v1/seller/products/" + PRODUCT + "/stock";
    }

    @Test
    void requestWithoutTokenIs401() throws IOException {
        Response response = gateway.get(stockPath());
        assertEquals(401, response.status());
        assertEquals("unauthenticated", response.json().get("code"));
        assertTrue(response.header("Content-Type").startsWith("application/problem+json"));
    }

    @Test
    void buyerTokenIsForbiddenOnSellerRoutes() throws IOException {
        String buyer = TOKENS.token().subject(SELLER).scopes("orders.read").roles("buyer").amr("pwd").build();
        Response response = gateway.get(stockPath(), StandHttp.bearer(buyer));
        assertEquals(403, response.status());
        assertEquals("forbidden", response.json().get("code"));
    }

    @Test
    void routeNotInContractIsNotFound() throws IOException {
        String seller = TOKENS.token().subject(SELLER).scopes("seller.catalog").roles("seller").amr("pwd", "otp").build();
        Response response = gateway.get("/api/v1/seller/vouchers", StandHttp.bearer(seller));
        assertEquals(404, response.status());
        assertEquals("not-found", response.json().get("code"));
    }

    @Test
    void internalRouteRefusesAServiceThatIsNotInTheCallerList() throws IOException {
        Response response = StandHttp.asService("order-service", PORT).get("/internal/v1/keys");
        assertEquals(403, response.status());
        assertEquals("forbidden", response.json().get("code"));
    }

    @Test
    void connectionWithoutClientCertificateIsRefused() {
        assertThrows(IOException.class, () -> StandHttp.withoutCertificate(PORT).get(stockPath()));
    }

    @Test
    void readinessAndMetricsOnManagementPort() throws IOException {
        assertEquals(200, monitoring.get("/actuator/health/readiness").status());
        assertTrue(monitoring.get("/actuator/prometheus").body().contains("application=\"inventory-service\""));
    }
}
