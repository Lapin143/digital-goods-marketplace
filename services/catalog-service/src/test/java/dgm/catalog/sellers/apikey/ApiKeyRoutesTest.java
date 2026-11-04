package dgm.catalog.sellers.apikey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.route.RoutePolicy;
import dgm.kit.route.RouteRule;
import dgm.kit.security.CallerFilter;
import dgm.kit.security.GatewayIdentity;
import dgm.kit.security.JwtFilter;
import dgm.kit.testing.TestCertificates;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.BadJwtException;

/**
 * Правила маршрутов API-ключей из настоящего {@code dgm/routes.json}: выпуск, список и отзыв только по токену продавца с областью
 * {@code seller.catalog}, вторым фактором и без сессии по SMS, а ключом их вызвать нельзя, даже если шлюз поручился за ключ (Р-185,
 * ADR-027). Проверка ключа внутренняя, только для {@code api-gateway}.
 */
@Tag("TC-952")
class ApiKeyRoutesTest {

    private static final RoutePolicy POLICY = RoutePolicy.fromClasspath("dgm/routes.json");
    private static final String BASE = "/api/v1/seller/api-keys";
    private static final String SELLER = "0199e0a0-0000-7000-8000-000000000101";

    private static RouteRule rule(String method, String path) {
        return POLICY.match(method, path).orElseThrow(() -> new AssertionError("Нет правила для " + method + " " + path));
    }

    @Test
    void issueListAndRevokeAreForTheSellerTokenWithTheCatalogScopeAndNoSmsSession() {
        for (RouteRule rule : List.of(rule("POST", BASE), rule("GET", BASE), rule("POST", BASE + "/" + UUID.randomUUID() + "/revocation"))) {
            assertEquals(List.of("seller.catalog"), rule.scopes(), rule.operationId());
            assertEquals(List.of("seller"), rule.roles(), rule.operationId());
            assertTrue(rule.smsSessionDenied(), rule.operationId());
            assertEquals(List.of(), rule.callers(), rule.operationId());
            assertFalse(rule.sellerApi(), rule.operationId() + ": ключом эту операцию вызывать нельзя");
            assertFalse(rule.keyOnly(), rule.operationId());
        }
    }

    @Test
    void issueAndRevokeNeedAnIdempotencyKeyAndRunInTheSellerOnboardingModule() {
        for (RouteRule rule : List.of(rule("POST", BASE), rule("POST", BASE + "/" + UUID.randomUUID() + "/revocation"))) {
            assertTrue(rule.idempotent(), rule.operationId());
            assertEquals("seller_onboarding", rule.module(), rule.operationId());
        }
        assertFalse(rule("GET", BASE).idempotent());
    }

    @Test
    void theOperationIdentifiersAreTheOnesOfTheContract() {
        assertEquals("createApiKey", rule("POST", BASE).operationId());
        assertEquals("listApiKeys", rule("GET", BASE).operationId());
        assertEquals("revokeApiKey", rule("POST", BASE + "/" + UUID.randomUUID() + "/revocation").operationId());
        assertEquals("checkApiKey", rule("POST", "/internal/v1/api-keys/check").operationId());
    }

    @Test
    void theGatewayCheckIsInternalAndOpenOnlyToTheGateway() {
        RouteRule check = rule("POST", "/internal/v1/api-keys/check");
        assertTrue(check.internal());
        assertEquals(List.of("api-gateway"), check.callers());
        assertEquals(List.of(), check.scopes());
        assertEquals(List.of(), check.roles());
        assertFalse(check.idempotent(), "проверка это чтение по POST, ключ идемпотентности ей не нужен");
        assertFalse(check.sellerApi());
    }

    @Test
    void aKeyThatTheGatewayVouchesForIsStillUnauthenticatedOnTheCabinetOperations() throws Exception {
        X509Certificate gateway = TestCertificates.selfSigned("api-gateway").certificate();
        JwtFilter filter = new JwtFilter(POLICY, token -> {
            throw new BadJwtException("токена нет");
        });
        for (String[] call : List.of(new String[] {"POST", BASE}, new String[] {"GET", BASE},
                new String[] {"POST", BASE + "/" + UUID.randomUUID() + "/revocation"})) {
            MockHttpServletRequest request = new MockHttpServletRequest(call[0], call[1]);
            request.setAttribute(CallerFilter.CERTIFICATE_ATTRIBUTE, new X509Certificate[] {gateway});
            request.addHeader(GatewayIdentity.SELLER_ID_HEADER, SELLER);
            request.addHeader(GatewayIdentity.AUTH_KIND_HEADER, GatewayIdentity.AUTH_KIND_API_KEY);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertEquals(401, response.getStatus(), call[0] + " " + call[1]);
            assertEquals("unauthenticated", Json.parseObject(response.getContentAsString()).get("code"));
            assertNull(chain.getRequest(), "запрос не должен дойти до контроллера");
            assertNull(request.getAttribute(JwtFilter.USER_ATTRIBUTE));
        }
    }
}
