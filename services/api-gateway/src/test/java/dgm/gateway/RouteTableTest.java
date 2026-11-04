package dgm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.route.RouteRule;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
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
        assertFalse(t.requiresAuth());
        assertEquals("catalog-service", found("GET", "/api/v1/products/0199e0a0-0000-4000-8000-000000000001").service());
    }

    @Test
    void protectedRouteNeedsTokenAndCarriesRule() {
        RouteTable.Target t = found("POST", "/api/v1/orders");
        assertEquals("order-service", t.service());
        assertTrue(t.requiresAuth());
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
        assertFalse(t.requiresAuth());
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

    // ---------------------------------------------------------------------------------------------------- R2: вход по API-ключу

    @Test
    @Tag("TC-955")
    void routesWithTheKeySchemeAreOpenToKeyAndTokenOrToKeyOnly() {
        RouteTable.Target stock = found("GET", "/api/v1/seller/products/0199e0a0-0000-4000-8000-000000000001/stock");
        assertEquals(RouteTable.Kind.TOKEN_OR_API_KEY, stock.kind());
        assertTrue(stock.acceptsKey());
        assertTrue(stock.acceptsToken());
        assertTrue(stock.requiresAuth());
        assertTrue(stock.rule().sellerApi());
        assertEquals("seller", stock.limit(), "по токену группа кабинета");
        assertEquals("seller.api", stock.keyLimit(), "по ключу группа ключа (Р-188)");

        RouteTable.Target answer = found("POST", "/api/v1/seller-api/delivery-requests/0199e0a0-0000-7000-8000-000000000a01/answer");
        assertEquals(RouteTable.Kind.API_KEY, answer.kind());
        assertTrue(answer.acceptsKey());
        assertFalse(answer.acceptsToken(), "на маршруте только для ключа токен пользователя не принимается");
        assertEquals("seller.api", answer.limit());
        assertEquals("delivery-service", answer.service());
    }

    @Test
    @Tag("TC-955")
    void cabinetOperationsStayOnTheTokenOnly() {
        for (String[] route : new String[][] {{"POST", "/api/v1/seller/api-keys"}, {"GET", "/api/v1/seller/api-keys"},
                {"POST", "/api/v1/seller/api-keys/0199e0a0-0000-7000-8000-000000000901/revocation"}, {"GET", "/api/v1/seller/integration"},
                {"PUT", "/api/v1/seller/integration"}, {"POST", "/api/v1/seller/integration/secret-rotation"},
                {"GET", "/api/v1/seller/webhook-deliveries"}}) {
            RouteTable.Target target = found(route[0], route[1]);
            assertEquals(RouteTable.Kind.TOKEN, target.kind(), route[1]);
            assertFalse(target.acceptsKey(), route[1] + ": ключом эту операцию вызвать нельзя");
            assertEquals(target.limit(), target.keyLimit(), route[1]);
        }
    }

    @Test
    @Tag("TC-957")
    void uploadsShareOneLimitGroupWhateverTheLogin() {
        for (String path : new String[] {"/api/v1/seller/products/0199e0a0-0000-4000-8000-000000000001/key-batches",
                "/api/v1/seller/products/0199e0a0-0000-4000-8000-000000000001/key-files"}) {
            RouteTable.Target target = found("POST", path);
            assertEquals("seller-upload", target.limit(), path);
            assertEquals("seller-upload", target.keyLimit(), path + ": ключ и токен в одной группе, счёт по продавцу");
            assertEquals("inventory-service", target.service());
        }
    }

    @Test
    @Tag("TC-955")
    void everyRouteWithAKeyHasAKeyGroupAndAPolicyFlagAndOnlyThoseRoutes() {
        for (RouteRule rule : table.rules()) {
            RouteTable.Target target = table.target(rule.operationId()).orElseThrow();
            assertEquals(target.acceptsKey(), rule.sellerApi(), rule.operationId() + ": x-seller-api и вход по ключу совпадают");
            if (target.acceptsKey()) {
                assertTrue(java.util.List.of("seller.api", "seller-upload").contains(target.keyLimit()), rule.operationId() + ": " + target.keyLimit());
            } else {
                assertEquals(target.limit(), target.keyLimit(), rule.operationId());
            }
        }
    }

    @Test
    @Tag("TC-955")
    void theGatewayCheckOfAKeyIsInternalAndNotReachableFromOutside() {
        closed("POST", "/internal/v1/api-keys/check");
        closed("POST", "/api/v1/api-keys/check");
    }

    @Test
    void theAuthFieldOfTheTableIsReadWithDashes() {
        assertEquals(RouteTable.Kind.TOKEN_OR_API_KEY, RouteTable.Kind.parse("token-or-api-key"));
        assertEquals(RouteTable.Kind.API_KEY, RouteTable.Kind.parse("api-key"));
        assertEquals(RouteTable.Kind.TOKEN, RouteTable.Kind.parse("token"));
        assertThrows(IllegalArgumentException.class, () -> RouteTable.Kind.parse("apikey"));
    }

    @Test
    void aKeyGroupOnARouteWithoutTheKeyLoginIsATableError() {
        String broken = "{\"routes\":[{\"method\":\"GET\",\"path\":\"/api/v1/x\",\"operationId\":\"x\",\"service\":\"catalog-service\","
                + "\"auth\":\"token\",\"limit\":\"seller\",\"keyLimit\":\"seller.api\",\"scopes\":[\"seller.catalog\"],\"roles\":[\"seller\"]}]}";
        assertThrows(IllegalArgumentException.class, () -> RouteTable.fromJson(broken, ALL));
    }

    @Test
    void aKeyRouteWithoutAKeyGroupIsATableError() {
        String broken = "{\"routes\":[{\"method\":\"GET\",\"path\":\"/api/v1/x\",\"operationId\":\"x\",\"service\":\"catalog-service\","
                + "\"auth\":\"token-or-api-key\",\"limit\":\"seller\",\"scopes\":[\"seller.catalog\"],\"roles\":[\"seller\"],\"sellerApi\":true}]}";
        assertThrows(IllegalArgumentException.class, () -> RouteTable.fromJson(broken, ALL));
    }
}
