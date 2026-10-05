package dgm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.route.RouteRule;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Таблица маршрутов шлюза: что открыто, что закрыто и куда идёт запрос (ADR-021). Проверяется настоящая таблица, созданная из OpenAPI. */
class RouteTableTest {

    private static final Set<String> ALL = Set.of("catalog-service", "inventory-service", "order-service", "payment-service", "delivery-service",
            "platform-service", "keycloak", "object-storage", "web-app", "external-stubs");

    private final RouteTable table = RouteTable.fromClasspath("dgm/gateway-routes.json", ALL);

    private RouteTable.Target found(String method, String path) {
        Optional<RouteTable.Target> target = table.resolve(method, path);
        assertTrue(target.isPresent(), method + " " + path + " должен быть открыт");
        return target.get();
    }

    private void closed(String method, String path) {
        assertTrue(table.resolve(method, path).isEmpty(), method + " " + path + " должен быть закрыт (404)");
    }

    @Test
    void tableIsNotEmptyAndEveryOperationHasATarget() {
        assertFalse(table.rules().isEmpty());
        for (RouteRule rule : table.rules()) {
            RouteTable.Target target = table.target(rule.operationId()).orElseThrow(() -> new AssertionError("Нет цели у " + rule.operationId()));
            assertTrue(ALL.contains(target.service()), rule.operationId() + ": неизвестный сервис " + target.service());
            assertFalse(rule.internal(), rule.operationId() + ": внутренний маршрут не должен попасть в таблицу шлюза");
        }
    }

    @Test
    void storefrontIsPublic() {
        RouteTable.Target t = found("GET", "/api/v1/products");
        assertEquals("catalog-service", t.service());
        assertEquals(RouteTable.Kind.PUBLIC, t.kind());
        assertEquals("public", t.limit());
        assertFalse(t.requiresToken());
        assertEquals("catalog-service", found("GET", "/api/v1/products/0199e0a0-0000-4000-8000-000000000001").service());
    }

    @Test
    void protectedRouteNeedsTokenAndCarriesRule() {
        RouteTable.Target t = found("POST", "/api/v1/orders");
        assertEquals("order-service", t.service());
        assertTrue(t.requiresToken());
        assertEquals("order-create", t.limit());
        assertEquals(java.util.List.of("orders.create"), t.rule().scopes());
        assertTrue(t.rule().smsSessionDenied());
        assertEquals("buyer", found("GET", "/api/v1/orders").limit());
    }

    @Test
    void webhooksAreOpenForSignatureCheckedByTheService() {
        RouteTable.Target t = found("POST", "/api/v1/webhooks/payment-gateway");
        assertEquals("payment-service", t.service());
        assertEquals(RouteTable.Kind.WEBHOOK, t.kind());
        assertEquals("webhook", t.limit());
        assertFalse(t.requiresToken());
    }

    @Test
    void wrongMethodUnknownPathAndOtherVersionAreClosed() {
        closed("DELETE", "/api/v1/orders");
        closed("PATCH", "/api/v1/products");
        closed("GET", "/api/v1/unknown");
        closed("GET", "/api/v2/orders");
        closed("GET", "/api");
        closed("GET", "/api/");
    }

    @Test
    void internalRoutesAreNeverReachableFromOutside() {
        closed("GET", "/internal/v1/products/0199e0a0-0000-4000-8000-000000000001");
        closed("POST", "/internal/v1/anything");
        closed("GET", "/internal");
        closed("GET", "/actuator/prometheus");
        closed("GET", "/actuator");
    }

    @Test
    void trailingSlashIsTheSameRoute() {
        assertEquals("order-service", found("GET", "/api/v1/orders/").service());
    }

    @Test
    void keycloakIsOpenOnlyForLogin() {
        RouteTable.Target t = found("GET", "/auth/realms/dgm/protocol/openid-connect/certs");
        assertEquals("keycloak", t.service());
        assertEquals("auth", t.limit());
        assertEquals("keycloak", found("POST", "/auth/realms/dgm/protocol/openid-connect/token").service());
        assertEquals("keycloak", found("POST", "/auth/realms/dgm/login-actions/authenticate").service());
        RouteTable.Target resources = found("GET", "/auth/resources/26.8/login/keycloak/css/login.css");
        assertEquals("keycloak", resources.service());
        assertEquals("web", resources.limit(), "статические файлы страницы входа считаются как веб-интерфейс, а не как вход");
        closed("POST", "/auth/resources/x");
        closed("DELETE", "/auth/realms/dgm");
        closed("PUT", "/auth/realms/dgm");
        // консоль, Admin API, метрики, здоровье, начальная страница и realm master наружу не открываются
        closed("GET", "/auth/admin/realms/dgm/users");
        closed("GET", "/auth/admin/master/console/");
        closed("GET", "/auth/realms/master/protocol/openid-connect/auth");
        closed("POST", "/auth/realms/master/protocol/openid-connect/token");
        closed("GET", "/auth/metrics");
        closed("GET", "/auth/health/ready");
        closed("GET", "/auth/");
        closed("GET", "/auth");
        // «/authors» не начинается с «/auth/»: это обычный путь веб-интерфейса
        assertEquals("web-app", found("GET", "/authors").service());
    }

    @Test
    void filesGoToObjectStorageReadOnly() {
        RouteTable.Target t = found("GET", "/files/seller-documents/a/b");
        assertEquals("object-storage", t.service());
        assertEquals("files", t.limit());
        assertEquals("object-storage", found("HEAD", "/files/seller-documents/a/b").service());
        closed("PUT", "/files/seller-documents/a/b");
        closed("POST", "/files/seller-documents/a/b");
        closed("DELETE", "/files/seller-documents/a/b");
    }

    @Test
    void stubRoutesForTheStand() {
        assertEquals("external-stubs", found("GET", "/vkid/authorize").service());
        assertEquals("external-stubs", found("POST", "/payment/pay/session-1").service());
        closed("POST", "/vkid/authorize");
    }

    @Test
    void webInterfaceIsReadOnlyFallback() {
        RouteTable.Target t = found("GET", "/");
        assertEquals("web-app", t.service());
        assertEquals("web", t.limit());
        assertEquals("web-app", found("GET", "/assets/app.js").service());
        assertEquals("web-app", found("HEAD", "/orders").service());
        closed("POST", "/");
        closed("DELETE", "/assets/app.js");
    }

    @Test
    void routesToAbsentUpstreamsAreClosed() {
        RouteTable api = RouteTable.fromClasspath("dgm/gateway-routes.json", Set.of("catalog-service"));
        assertTrue(api.resolve("GET", "/").isEmpty(), "без web-app веб-интерфейса нет");
        assertTrue(api.resolve("GET", "/auth/realms/dgm").isEmpty(), "без keycloak /auth закрыт: путь не уходит в веб-интерфейс");
        assertTrue(api.resolve("GET", "/api/v1/products").isPresent());
    }
}
