package dgm.kit.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RoutePolicyTest {

    static final String POLICY = """
            {"service": "order-service", "routes": [
              {"method": "GET",  "path": "/api/v1/orders/{orderId}", "operationId": "getOrder", "scopes": ["orders.read"], "roles": ["buyer"]},
              {"method": "GET",  "path": "/api/v1/orders/current", "operationId": "getCurrent", "scopes": ["orders.read"], "roles": ["buyer"]},
              {"method": "GET",  "path": "/api/v1/orders", "operationId": "listOrders", "scopes": ["orders.read"], "roles": ["buyer"]},
              {"method": "POST", "path": "/api/v1/orders/{orderId}/cancel", "operationId": "cancelOrder", "scopes": ["orders.write"], "roles": ["buyer"], "smsSession": "denied"},
              {"method": "GET",  "path": "/api/v1/storefront/products", "operationId": "listStorefrontProducts"},
              {"method": "GET",  "path": "/internal/v1/products/{productId}", "operationId": "getProductCard", "callers": ["order-service"]}
            ]}
            """;

    private final RoutePolicy policy = RoutePolicy.fromJson(POLICY);

    @Test
    void readsServiceAndRules() {
        assertEquals("order-service", policy.service());
        assertEquals(6, policy.rules().size());
    }

    @Test
    void matchesTemplateAndFillsRule() {
        RouteRule rule = policy.match("GET", "/api/v1/orders/42").orElseThrow();
        assertEquals("getOrder", rule.operationId());
        assertEquals(List.of("orders.read"), rule.scopes());
        assertEquals(List.of("buyer"), rule.roles());
        assertTrue(rule.requiresToken());
        assertFalse(rule.internal());
    }

    @Test
    void exactPathBeatsTemplate() {
        assertEquals("getCurrent", policy.match("GET", "/api/v1/orders/current").orElseThrow().operationId());
    }

    @Test
    void methodMustMatch() {
        assertTrue(policy.match("DELETE", "/api/v1/orders/42").isEmpty());
        assertEquals("cancelOrder", policy.match("post", "/api/v1/orders/42/cancel").orElseThrow().operationId());
    }

    @Test
    void trailingSlashIsIgnoredAndExtraSegmentsAreNot() {
        assertEquals("listOrders", policy.match("GET", "/api/v1/orders/").orElseThrow().operationId());
        assertTrue(policy.match("GET", "/api/v1/orders/42/extra").isEmpty());
        assertTrue(policy.match("GET", "/api/v1").isEmpty());
    }

    @Test
    void publicRouteNeedsNoToken() {
        RouteRule rule = policy.match("GET", "/api/v1/storefront/products").orElseThrow();
        assertFalse(rule.requiresToken());
        assertFalse(rule.internal());
    }

    @Test
    void internalRouteListsCallers() {
        RouteRule rule = policy.match("GET", "/internal/v1/products/7").orElseThrow();
        assertTrue(rule.internal());
        assertEquals(List.of("order-service"), rule.callers());
    }

    @Test
    void smsSessionFlagIsRead() {
        assertTrue(policy.match("POST", "/api/v1/orders/1/cancel").orElseThrow().smsSessionDenied());
        assertFalse(policy.match("GET", "/api/v1/orders/1").orElseThrow().smsSessionDenied());
    }

    @Test
    void brokenPolicyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RoutePolicy.fromJson("{\"service\":\"x\"}"));
        assertThrows(IllegalArgumentException.class, () -> RoutePolicy.fromJson("{\"routes\":[{\"method\":\"GET\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> RoutePolicy.fromJson("{\"routes\":[{\"method\":\"GET\",\"path\":\"/a\",\"operationId\":\"x\",\"scopes\":\"orders.read\"}]}"));
    }

    @Test
    void missingResourceIsReported() {
        assertThrows(IllegalStateException.class, () -> RoutePolicy.fromClasspath("no/such/routes.json"));
    }

    @Test
    void pathGuardRejectsAmbiguousPaths() {
        assertTrue(PathGuard.isSafe("/api/v1/orders/42"));
        assertTrue(PathGuard.isSafe("/"));
        for (String bad : new String[] {"", "api/v1", "/api//v1", "/api/v1/orders;jsessionid=1", "/api/%2e%2e/admin", "/api/../admin",
                "/api/./x", "/api\\x", "/api/\u0000", "/api/заказ", "/api/ x"}) {
            assertFalse(PathGuard.isSafe(bad), bad);
        }
        assertFalse(PathGuard.isSafe(null));
    }
}
