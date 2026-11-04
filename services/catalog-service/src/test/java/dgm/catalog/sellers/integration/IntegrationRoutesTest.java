package dgm.catalog.sellers.integration;

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
 * Правила маршрутов подключения из настоящего {@code dgm/routes.json}: чтение, сохранение и смена секрета только по токену продавца с областью
 * {@code seller.catalog}, вторым фактором и без сессии по SMS, ключом их вызвать нельзя даже за поручительством шлюза (Р-185, ADR-027);
 * внутренний адрес открыт только {@code delivery-service} и {@code platform-service}, и пользовательскому токену в него хода нет.
 */
@Tag("TC-954")
@Tag("TC-959")
class IntegrationRoutesTest {

    private static final RoutePolicy POLICY = RoutePolicy.fromClasspath("dgm/routes.json");
    private static final String BASE = "/api/v1/seller/integration";
    private static final String SELLER = "0199e0a0-0000-7000-8000-000000000101";

    private static RouteRule rule(String method, String path) {
        return POLICY.match(method, path).orElseThrow(() -> new AssertionError("Нет правила для " + method + " " + path));
    }

    @Test
    void readSaveAndRotateAreForTheSellerTokenWithTheCatalogScopeAndNoSmsSession() {
        for (RouteRule rule : List.of(rule("GET", BASE), rule("PUT", BASE), rule("POST", BASE + "/secret-rotation"))) {
            assertEquals(List.of("seller.catalog"), rule.scopes(), rule.operationId());
            assertEquals(List.of("seller"), rule.roles(), rule.operationId());
            assertTrue(rule.smsSessionDenied(), rule.operationId());
            assertEquals(List.of(), rule.callers(), rule.operationId());
            assertFalse(rule.sellerApi(), rule.operationId() + ": ключом эту операцию вызывать нельзя");
            assertFalse(rule.keyOnly(), rule.operationId());
            assertFalse(rule.internal(), rule.operationId());
        }
    }

    @Test
    void onlyTheRotationNeedsAnIdempotencyKeyBecauseTheSaveIsGuardedByTheVersion() {
        assertTrue(rule("POST", BASE + "/secret-rotation").idempotent());
        assertFalse(rule("PUT", BASE).idempotent(), "сохранение защищено версией записи (If-Match), ключ идемпотентности ему не нужен");
        assertFalse(rule("GET", BASE).idempotent());
        assertEquals("seller_onboarding", rule("PUT", BASE).module());
        assertEquals("seller_onboarding", rule("POST", BASE + "/secret-rotation").module());
    }

    @Test
    void theOperationIdentifiersAreTheOnesOfTheContract() {
        assertEquals("getSellerIntegration", rule("GET", BASE).operationId());
        assertEquals("setSellerIntegration", rule("PUT", BASE).operationId());
        assertEquals("rotateSellerIntegrationSecret", rule("POST", BASE + "/secret-rotation").operationId());
        assertEquals("getSellerEndpoint", rule("GET", "/internal/v1/sellers/" + UUID.randomUUID() + "/integration").operationId());
    }

    @Test
    void theInternalAddressIsOpenOnlyToTheDeliveryAndPlatformServices() {
        RouteRule endpoint = rule("GET", "/internal/v1/sellers/" + UUID.randomUUID() + "/integration");
        assertTrue(endpoint.internal());
        assertEquals(List.of("delivery-service", "platform-service"), endpoint.callers());
        assertEquals(List.of(), endpoint.scopes());
        assertEquals(List.of(), endpoint.roles());
        assertFalse(endpoint.sellerApi());
        assertFalse(endpoint.idempotent());
    }

    @Test
    void aKeyThatTheGatewayVouchesForIsStillUnauthenticatedOnTheIntegrationOperations() throws Exception {
        X509Certificate gateway = TestCertificates.selfSigned("api-gateway").certificate();
        JwtFilter filter = new JwtFilter(POLICY, token -> {
            throw new BadJwtException("токена нет");
        });
        for (String[] call : List.of(new String[] {"GET", BASE}, new String[] {"PUT", BASE}, new String[] {"POST", BASE + "/secret-rotation"})) {
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
