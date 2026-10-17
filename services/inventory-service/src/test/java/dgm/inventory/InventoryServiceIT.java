package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import java.io.IOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Сервис инвентаря целиком на стенде (набор dev-kit с отладочными портами): запуск с миграциями под ролью-мигратором, проверка токена и
 * вызывающего по сертификату, правила маршрутов из OpenAPI, порт управления. Операции пула ключей проверяет {@code KeyPoolIT}.
 * Ответы складываются в build/contract-samples и проверяются {@code check_contract.py}.
 */
class InventoryServiceIT extends InventoryStandIT {

    private static final String SELLER = "0199e0a0-0000-4000-8000-0000000000b1";
    private static final String PRODUCT = "0199e0a0-0000-7000-8000-000000000201";

    private static StandHttp gateway;
    private static StandHttp monitoring;

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
