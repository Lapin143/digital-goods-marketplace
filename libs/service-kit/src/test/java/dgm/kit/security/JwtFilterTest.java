package dgm.kit.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.route.RoutePolicy;
import dgm.kit.testing.TestTokens;
import dgm.kit.time.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Проверка токена: подпись, издатель, адресат, срок, область, роль, второй фактор, сессия по SMS, неизвестные и «хитрые» пути. */
class JwtFilterTest {

    private static final String POLICY = """
            {"service": "order-service", "routes": [
              {"method": "GET",  "path": "/api/v1/orders", "operationId": "listOrders", "scopes": ["orders.read"], "roles": ["buyer"]},
              {"method": "POST", "path": "/api/v1/orders/{orderId}/cancel", "operationId": "cancelOrder", "scopes": ["orders.write"], "roles": ["buyer"], "smsSession": "denied"},
              {"method": "GET",  "path": "/api/v1/seller/products", "operationId": "listSellerProducts", "scopes": ["products.read"], "roles": ["seller"]},
              {"method": "GET",  "path": "/api/v1/storefront/products", "operationId": "listStorefrontProducts"},
              {"method": "GET",  "path": "/internal/v1/products/{productId}", "operationId": "getProductCard", "callers": ["order-service"]}
            ]}
            """;

    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-04T10:00:00Z"));
    private final TestTokens tokens = TestTokens.create(clock);
    private final JwtSettings settings = new JwtSettings(TestTokens.ISSUER, null);
    private final JwtDecoder decoder = JwtDecoders.forPublicKey(tokens.publicKey(), settings, clock);
    private final JwtFilter filter = new JwtFilter(RoutePolicy.fromJson(POLICY), decoder);

    private record Result(MockHttpServletRequest request, MockHttpServletResponse response, MockFilterChain chain) {
        boolean passed() {
            return chain.getRequest() != null;
        }

        String code() throws Exception {
            return (String) Json.parseObject(response.getContentAsString()).get("code");
        }
    }

    private Result call(String method, String path, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(request, response, chain);
    }

    private Result get(String path, String token) throws Exception {
        return call("GET", path, token == null ? null : "Bearer " + token);
    }

    @Test
    void validTokenPassesAndUserIsAvailable() throws Exception {
        Result r = get("/api/v1/orders", tokens.token().build());
        assertTrue(r.passed());
        AuthenticatedUser user = (AuthenticatedUser) r.request().getAttribute(JwtFilter.USER_ATTRIBUTE);
        assertNotNull(user);
        assertEquals("11111111-1111-4111-8111-111111111111", user.subject());
        assertNotNull(r.request().getAttribute(JwtFilter.ROUTE_ATTRIBUTE));
    }

    @Test
    void missingTokenIsUnauthenticatedWithChallenge() throws Exception {
        Result r = get("/api/v1/orders", null);
        assertEquals(401, r.response().getStatus());
        assertEquals("unauthenticated", r.code());
        assertEquals("Bearer", r.response().getHeader("WWW-Authenticate"));
        assertTrue(!r.passed());
    }

    @Test
    void otherSchemeOrEmptyBearerIsUnauthenticated() throws Exception {
        assertEquals(401, call("GET", "/api/v1/orders", "Basic dXNlcjpwYXNz").response().getStatus());
        assertEquals(401, call("GET", "/api/v1/orders", "Bearer ").response().getStatus());
        assertEquals(401, call("GET", "/api/v1/orders", "Bearer").response().getStatus());
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        assertTrue(call("GET", "/api/v1/orders", "bearer " + tokens.token().build()).passed());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        Result r = get("/api/v1/orders", tokens.token().signedWith(TestTokens.newPair()).build());
        assertEquals(401, r.response().getStatus());
        assertEquals("Bearer error=\"invalid_token\"", r.response().getHeader("WWW-Authenticate"));
        assertTrue(!r.passed());
    }

    @Test
    void algorithmNoneIsRejected() throws Exception {
        Result r = get("/api/v1/orders", tokens.token().buildUnsigned());
        assertEquals(401, r.response().getStatus());
        assertTrue(!r.passed());
    }

    @Test
    void foreignIssuerIsRejected() throws Exception {
        Result r = get("/api/v1/orders", tokens.token().issuer("https://evil.example/realms/dgm").build());
        assertEquals(401, r.response().getStatus());
    }

    @Test
    void foreignAudienceIsRejected() throws Exception {
        assertEquals(401, get("/api/v1/orders", tokens.token().audience("other-api").build()).response().getStatus());
        assertEquals(401, get("/api/v1/orders", tokens.token().audience().build()).response().getStatus());
    }

    @Test
    void audienceListContainingOursIsAccepted() throws Exception {
        assertTrue(get("/api/v1/orders", tokens.token().audience("account", "dgm-api").build()).passed());
    }

    @Test
    void expiredTokenIsRejectedButClockSkewIsTolerated() throws Exception {
        String token = tokens.token().expiresIn(Duration.ofMinutes(5)).build();
        clock.advance(Duration.ofMinutes(5).plusSeconds(20));
        assertTrue(get("/api/v1/orders", token).passed(), "допуск расхождения часов 30 секунд");
        clock.advance(Duration.ofSeconds(20));
        assertEquals(401, get("/api/v1/orders", token).response().getStatus());
    }

    @Test
    void tokenIssuedAlreadyExpiredIsRejected() throws Exception {
        assertEquals(401, get("/api/v1/orders", tokens.token().expiresIn(Duration.ofMinutes(-10)).build()).response().getStatus());
    }

    @Test
    void missingScopeIsForbidden() throws Exception {
        Result r = get("/api/v1/orders", tokens.token().scopes("catalog.read").build());
        assertEquals(403, r.response().getStatus());
        assertEquals("forbidden", r.code());
        assertTrue(!r.passed());
    }

    @Test
    void wrongRoleIsForbidden() throws Exception {
        Result r = get("/api/v1/orders", tokens.token().roles("moderator").amr("pwd", "otp").build());
        assertEquals(403, r.response().getStatus());
        assertEquals("forbidden", r.code());
    }

    @Test
    void sellerWithoutSecondFactorIsRejected() throws Exception {
        String pwdOnly = tokens.token().roles("seller").scopes("products.read").amr("pwd").build();
        Result r = get("/api/v1/seller/products", pwdOnly);
        assertEquals(403, r.response().getStatus());
        assertEquals("second-factor-required", r.code());
        String withOtp = tokens.token().roles("seller").scopes("products.read").amr("pwd", "otp").build();
        assertTrue(get("/api/v1/seller/products", withOtp).passed());
    }

    @Test
    void smsSessionCannotCancelOrder() throws Exception {
        String sms = tokens.token().scopes("orders.write").amr("sms").build();
        Result r = call("POST", "/api/v1/orders/1/cancel", "Bearer " + sms);
        assertEquals(403, r.response().getStatus());
        assertEquals("full-login-required", r.code());
        String full = tokens.token().scopes("orders.write").amr("pwd").build();
        assertTrue(call("POST", "/api/v1/orders/1/cancel", "Bearer " + full).passed());
    }

    @Test
    void publicRoutePassesWithoutToken() throws Exception {
        Result r = get("/api/v1/storefront/products", null);
        assertTrue(r.passed());
        assertNull(r.request().getAttribute(JwtFilter.USER_ATTRIBUTE));
    }

    @Test
    void internalRoutePassesToCallerFilter() throws Exception {
        Result r = get("/internal/v1/products/5", null);
        assertTrue(r.passed());
    }

    @Test
    void routeMissingFromContractIsNotFound() throws Exception {
        Result r = get("/api/v1/admin/everything", tokens.token().build());
        assertEquals(404, r.response().getStatus());
        assertEquals("not-found", r.code());
        assertTrue(!r.passed());
        assertEquals(404, call("DELETE", "/api/v1/orders", "Bearer " + tokens.token().build()).response().getStatus());
    }

    @Test
    void trickyPathsAreRejectedBeforeMatching() throws Exception {
        for (String path : new String[] {"/api/v1//orders", "/api/v1/orders;x=1", "/api/v1/%6frders", "/api/v1/storefront/../orders"}) {
            Result r = get(path, null);
            assertEquals(400, r.response().getStatus(), path);
            assertTrue(!r.passed(), path);
        }
    }

    @Test
    void keysUnavailableGivesServiceUnavailableNotUnauthorized() throws Exception {
        JwtDecoder broken = token -> {
            throw new JwtException("Couldn't retrieve remote JWK set");
        };
        JwtFilter f = new JwtFilter(RoutePolicy.fromJson(POLICY), broken);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader("Authorization", "Bearer abc.def.ghi");
        MockHttpServletResponse response = new MockHttpServletResponse();
        f.doFilter(request, response, new MockFilterChain());
        assertEquals(503, response.getStatus());
        assertEquals("dependency-unavailable", Json.parseObject(response.getContentAsString()).get("code"));
    }

    @Test
    void errorBodiesDoNotRevealTokenOrReason() throws Exception {
        String token = tokens.token().signedWith(TestTokens.newPair()).build();
        String body = get("/api/v1/orders", token).response().getContentAsString();
        assertTrue(!body.contains(token));
        assertTrue(!body.toLowerCase().contains("signature"));
        Map<String, Object> parsed = Json.parseObject(body);
        assertEquals("Токен недействителен.", parsed.get("detail"));
    }
}
