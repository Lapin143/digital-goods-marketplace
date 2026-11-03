package dgm.kit.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.route.RoutePolicy;
import dgm.kit.testing.TestCertificates;
import dgm.kit.testing.TestTokens;
import dgm.kit.time.ManualClock;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Личность по API-ключу (ADR-027, Р-186): заголовки шлюза принимаются только от сертификата {@code api-gateway} и только на маршрутах с
 * {@code x-seller-api}. Подделка заголовка без сертификата шлюза не даёт личности (T-45, ST-02).
 */
class JwtFilterSellerApiTest {

    private static final String SELLER = "0199e0a0-0000-7000-8000-000000000101";
    private static final String OTHER_SELLER = "0199e0a0-0000-7000-8000-000000000202";

    private static final String POLICY = """
            {"service": "delivery-service", "routes": [
              {"method": "GET",  "path": "/api/v1/seller/products", "operationId": "listSellerProducts", "scopes": ["products.read"], "roles": ["seller"], "sellerApi": true},
              {"method": "POST", "path": "/api/v1/seller-api/delivery-requests/{requestId}/answer", "operationId": "respondSellerDelivery", "scopes": ["seller.api"], "roles": ["seller"], "sellerApi": true},
              {"method": "POST", "path": "/api/v1/seller/products", "operationId": "createProduct", "scopes": ["products.write"], "roles": ["seller"]},
              {"method": "GET",  "path": "/api/v1/storefront/products", "operationId": "listStorefrontProducts"},
              {"method": "GET",  "path": "/internal/v1/seller-endpoints/{sellerId}", "operationId": "getSellerEndpoint", "callers": ["delivery-service"], "sellerApi": true}
            ]}
            """;

    private static X509Certificate gateway;
    private static X509Certificate gatewayLookalike;
    private static X509Certificate otherService;

    @BeforeAll
    static void certificates() {
        gateway = TestCertificates.selfSigned("api-gateway").certificate();
        gatewayLookalike = TestCertificates.selfSigned("api-gateway-2").certificate();
        otherService = TestCertificates.selfSigned("order-service").certificate();
    }

    private final ManualClock clock = new ManualClock(Instant.parse("2026-11-03T10:00:00Z"));
    private final TestTokens tokens = TestTokens.create(clock);
    private final JwtDecoder decoder = JwtDecoders.forPublicKey(tokens.publicKey(), new JwtSettings(TestTokens.ISSUER, null), clock);
    private final JwtFilter filter = new JwtFilter(RoutePolicy.fromJson(POLICY), decoder);

    private record Result(MockHttpServletRequest request, MockHttpServletResponse response, MockFilterChain chain) {
        boolean passed() {
            return chain.getRequest() != null;
        }

        AuthenticatedUser user() {
            return (AuthenticatedUser) request.getAttribute(JwtFilter.USER_ATTRIBUTE);
        }

        String code() throws Exception {
            return (String) Json.parseObject(response.getContentAsString()).get("code");
        }

        String detail() throws Exception {
            return (String) Json.parseObject(response.getContentAsString()).get("detail");
        }
    }

    /** Запрос с заголовками шлюза (если заданы), сертификатом клиента (если задан) и токеном (если задан). */
    private Result call(String method, String path, X509Certificate certificate, String sellerId, String kind, String bearer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (certificate != null) {
            request.setAttribute(CallerFilter.CERTIFICATE_ATTRIBUTE, new X509Certificate[] {certificate});
        }
        if (sellerId != null) {
            request.addHeader(GatewayIdentity.SELLER_ID_HEADER, sellerId);
        }
        if (kind != null) {
            request.addHeader(GatewayIdentity.AUTH_KIND_HEADER, kind);
        }
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(request, response, chain);
    }

    private static final String ANSWER = "/api/v1/seller-api/delivery-requests/7f3a/answer";

    private String sellerToken() {
        return tokens.token().subject("0199e0a0-0000-7000-8000-0000000000aa").roles("seller").scopes("products.read", "products.write")
                .amr("pwd", "otp").sellerId(SELLER).build();
    }

    @Test
    void gatewayIdentityOpensAKeyOnlyRoute() throws Exception {
        Result r = call("POST", ANSWER, gateway, SELLER, "api-key", null);
        assertTrue(r.passed());
        AuthenticatedUser user = r.user();
        assertNotNull(user);
        assertTrue(user.apiKey());
        assertEquals(SELLER, user.sellerId());
        assertEquals(SELLER, user.subject());
        assertEquals(java.util.Set.of("seller"), user.roles());
        assertEquals(java.util.Set.of("seller.api"), user.scopes());
        assertEquals(UUID.fromString(SELLER), CurrentUser.sellerId(user));
        assertNotNull(r.request().getAttribute(JwtFilter.ROUTE_ATTRIBUTE));
    }

    @Test
    void gatewayIdentityAlsoOpensARouteThatAcceptsTokensAndKeys() throws Exception {
        Result r = call("GET", "/api/v1/seller/products", gateway, SELLER, "api-key", null);
        assertTrue(r.passed());
        assertTrue(r.user().apiKey());
        assertEquals(SELLER, r.user().sellerId());
    }

    @Test
    void uppercaseIdentifierIsNormalizedToCanonicalForm() throws Exception {
        Result r = call("POST", ANSWER, gateway, SELLER.toUpperCase(java.util.Locale.ROOT), "api-key", null);
        assertTrue(r.passed());
        assertEquals(SELLER, r.user().sellerId());
    }

    @Test
    void tokenStillWorksOnARouteThatAcceptsBoth() throws Exception {
        Result r = call("GET", "/api/v1/seller/products", null, null, null, sellerToken());
        assertTrue(r.passed());
        assertFalse(r.user().apiKey());
        assertEquals("0199e0a0-0000-7000-8000-0000000000aa", r.user().subject());
        assertEquals(SELLER, r.user().sellerId());
    }

    @Test
    void keyOnlyRouteRejectsRequestsWithoutAKeyIdentity() throws Exception {
        Result none = call("POST", ANSWER, null, null, null, null);
        assertEquals(401, none.response().getStatus());
        assertEquals("unauthenticated", none.code());
        assertEquals("Нужен API-ключ продавца.", none.detail());
        assertFalse(none.passed());
        // Токен продавца там, где нужен ключ, не принимается: у токена нет области seller.api
        Result token = call("POST", ANSWER, null, null, null, sellerToken());
        assertEquals(401, token.response().getStatus());
        assertFalse(token.passed());
        Result tokenViaGateway = call("POST", ANSWER, gateway, null, null, sellerToken());
        assertEquals(401, tokenViaGateway.response().getStatus());
        assertFalse(tokenViaGateway.passed());
    }

    @Test
    void headersWithoutTheGatewayCertificateGiveNoIdentity() throws Exception {
        // Прямой вызов сервиса с поддельными заголовками: ни сертификата, ни чужой сертификат, ни похожее имя
        for (X509Certificate certificate : new X509Certificate[] {null, otherService, gatewayLookalike}) {
            Result r = call("POST", ANSWER, certificate, OTHER_SELLER, "api-key", null);
            assertEquals(401, r.response().getStatus(), String.valueOf(certificate));
            assertEquals("unauthenticated", r.code());
            assertFalse(r.passed());
            assertNull(r.user());
        }
    }

    @Test
    void forgedHeadersNeverOverrideARealToken() throws Exception {
        // Маршрут принимает и токен, и ключ; заголовки чужого продавца приходят не от шлюза. Личность остаётся из токена
        for (X509Certificate certificate : new X509Certificate[] {null, otherService, gatewayLookalike}) {
            Result r = call("GET", "/api/v1/seller/products", certificate, OTHER_SELLER, "api-key", sellerToken());
            assertTrue(r.passed(), String.valueOf(certificate));
            assertFalse(r.user().apiKey());
            assertEquals(SELLER, r.user().sellerId());
        }
    }

    @Test
    void headersAreIgnoredOnRoutesWithoutTheSellerApiFlag() throws Exception {
        // Шлюз не должен был передать заголовки на такой маршрут, но даже с его сертификатом сервис их не принимает
        Result noToken = call("POST", "/api/v1/seller/products", gateway, SELLER, "api-key", null);
        assertEquals(401, noToken.response().getStatus());
        assertEquals("Нужен токен доступа в заголовке Authorization.", noToken.detail());
        assertFalse(noToken.passed());
        Result withToken = call("POST", "/api/v1/seller/products", gateway, OTHER_SELLER, "api-key", sellerToken());
        assertTrue(withToken.passed());
        assertFalse(withToken.user().apiKey());
        assertEquals(SELLER, withToken.user().sellerId());
        // Публичный маршрут заголовков не читает вовсе
        Result publicRoute = call("GET", "/api/v1/storefront/products", gateway, SELLER, "api-key", null);
        assertTrue(publicRoute.passed());
        assertNull(publicRoute.user());
    }

    @Test
    void tokenAndKeyTogetherAreRefused() throws Exception {
        Result r = call("GET", "/api/v1/seller/products", gateway, SELLER, "api-key", sellerToken());
        assertEquals(401, r.response().getStatus());
        assertEquals("Передайте токен или API-ключ, но не оба сразу.", r.detail());
        assertFalse(r.passed());
        Result keyOnly = call("POST", ANSWER, gateway, SELLER, "api-key", "anything");
        assertEquals(401, keyOnly.response().getStatus());
        assertFalse(keyOnly.passed());
    }

    @Test
    void incompleteOrWrongGatewayHeadersAreRefusedEvenOnARouteWithTokens() throws Exception {
        String[][] bad = {
            {SELLER, null},                 // нет вида входа
            {null, "api-key"},              // нет идентификатора
            {SELLER, "jwt"},                // другой вид входа
            {SELLER, "API-KEY"},            // регистр вида входа значим
            {"not-a-uuid", "api-key"},
            {"", "api-key"},
            {"1-1-1-1-1", "api-key"},       // Java разобрала бы это как UUID, но это не канонический вид
            {SELLER + "0", "api-key"},
            {"0199e0a0000070008000000000000101", "api-key"},
        };
        for (String[] pair : bad) {
            Result keyRoute = call("POST", ANSWER, gateway, pair[0], pair[1], null);
            assertEquals(401, keyRoute.response().getStatus(), String.join("|", String.valueOf(pair[0]), String.valueOf(pair[1])));
            assertEquals("Личность по API-ключу не распознана.", keyRoute.detail());
            assertFalse(keyRoute.passed());
            // Токен рядом с негодными заголовками шлюза не спасает: шлюз сам ошибся, это ошибка настройки, а не запрос пользователя
            Result tokenRoute = call("GET", "/api/v1/seller/products", gateway, pair[0], pair[1], sellerToken());
            assertEquals(401, tokenRoute.response().getStatus(), String.join("|", String.valueOf(pair[0]), String.valueOf(pair[1])));
            assertFalse(tokenRoute.passed());
        }
    }

    @Test
    void internalRoutesAreNotTouchedByKeyIdentity() throws Exception {
        // Внутренний маршрут проверяет CallerFilter; JwtFilter пропускает его без чтения заголовков личности
        Result r = call("GET", "/internal/v1/seller-endpoints/" + SELLER, gateway, OTHER_SELLER, "api-key", null);
        assertTrue(r.passed());
        assertNull(r.user());
    }

    @Test
    void unknownRouteIsStillNotFoundForTheGateway() throws Exception {
        Result r = call("POST", "/api/v1/seller-api/unknown", gateway, SELLER, "api-key", null);
        assertEquals(404, r.response().getStatus());
        assertFalse(r.passed());
    }
}
