package dgm.kit.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.route.RouteRule;
import dgm.kit.testing.TestCertificates;
import java.security.cert.X509Certificate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Когда сервис верит заголовкам личности от шлюза: сертификат {@code api-gateway}, маршрут {@code x-seller-api}, оба заголовка в каноническом виде. */
class GatewayIdentityTest {

    private static final String SELLER = "0199e0a0-0000-7000-8000-000000000101";
    private static final RouteRule KEY_ROUTE = new RouteRule("POST", "/k", "op", List.of("seller.api"), List.of("seller"), false, List.of(), false, null, null, false, true);
    private static final RouteRule PLAIN_ROUTE = new RouteRule("POST", "/p", "op", List.of("products.write"), List.of("seller"), false, List.of());

    private static X509Certificate gateway;
    private static X509Certificate other;

    @BeforeAll
    static void certificates() {
        gateway = TestCertificates.selfSigned("api-gateway").certificate();
        other = TestCertificates.selfSigned("catalog-service").certificate();
    }

    private static MockHttpServletRequest request(X509Certificate certificate, String sellerId, String kind) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/k");
        if (certificate != null) {
            request.setAttribute(CallerFilter.CERTIFICATE_ATTRIBUTE, new X509Certificate[] {certificate});
        }
        if (sellerId != null) {
            request.addHeader(GatewayIdentity.SELLER_ID_HEADER, sellerId);
        }
        if (kind != null) {
            request.addHeader(GatewayIdentity.AUTH_KIND_HEADER, kind);
        }
        return request;
    }

    @Test
    void trustedOnlyFromTheGatewayOnAKeyRoute() {
        GatewayIdentity.Result r = GatewayIdentity.resolve(request(gateway, SELLER, "api-key"), KEY_ROUTE);
        assertEquals(GatewayIdentity.Status.TRUSTED, r.status());
        assertTrue(r.trusted());
        assertEquals(SELLER, r.sellerId());
    }

    @Test
    void headersAreNotReadOnARouteWithoutTheFlag() {
        GatewayIdentity.Result r = GatewayIdentity.resolve(request(gateway, SELLER, "api-key"), PLAIN_ROUTE);
        assertEquals(GatewayIdentity.Status.ABSENT, r.status());
        assertFalse(r.trusted());
        assertNull(r.sellerId());
        // даже когда заголовки негодные: на таком маршруте они не значат ничего
        assertEquals(GatewayIdentity.Status.ABSENT, GatewayIdentity.resolve(request(gateway, "junk", "jwt"), PLAIN_ROUTE).status());
    }

    @Test
    void headersFromAnyoneButTheGatewayAreNotRead() {
        assertEquals(GatewayIdentity.Status.ABSENT, GatewayIdentity.resolve(request(null, SELLER, "api-key"), KEY_ROUTE).status());
        assertEquals(GatewayIdentity.Status.ABSENT, GatewayIdentity.resolve(request(other, SELLER, "api-key"), KEY_ROUTE).status());
        // Негодные заголовки не от шлюза тоже просто не читаются: ошибки в них нет
        assertEquals(GatewayIdentity.Status.ABSENT, GatewayIdentity.resolve(request(other, "junk", null), KEY_ROUTE).status());
        MockHttpServletRequest wrongAttribute = request(null, SELLER, "api-key");
        wrongAttribute.setAttribute(CallerFilter.CERTIFICATE_ATTRIBUTE, "api-gateway");
        assertEquals(GatewayIdentity.Status.ABSENT, GatewayIdentity.resolve(wrongAttribute, KEY_ROUTE).status());
        MockHttpServletRequest emptyChain = request(null, SELLER, "api-key");
        emptyChain.setAttribute(CallerFilter.CERTIFICATE_ATTRIBUTE, new X509Certificate[0]);
        assertEquals(GatewayIdentity.Status.ABSENT, GatewayIdentity.resolve(emptyChain, KEY_ROUTE).status());
    }

    @Test
    void noHeadersFromTheGatewayMeansAbsentAndHalfHeadersMeanMalformed() {
        assertEquals(GatewayIdentity.Status.ABSENT, GatewayIdentity.resolve(request(gateway, null, null), KEY_ROUTE).status());
        assertEquals(GatewayIdentity.Status.MALFORMED, GatewayIdentity.resolve(request(gateway, SELLER, null), KEY_ROUTE).status());
        assertEquals(GatewayIdentity.Status.MALFORMED, GatewayIdentity.resolve(request(gateway, null, "api-key"), KEY_ROUTE).status());
        assertEquals(GatewayIdentity.Status.MALFORMED, GatewayIdentity.resolve(request(gateway, SELLER, "bearer"), KEY_ROUTE).status());
        assertEquals(GatewayIdentity.Status.MALFORMED, GatewayIdentity.resolve(request(gateway, "not-a-uuid", "api-key"), KEY_ROUTE).status());
        assertEquals(GatewayIdentity.Status.MALFORMED, GatewayIdentity.resolve(request(gateway, "1-1-1-1-1", "api-key"), KEY_ROUTE).status());
    }

    @Test
    void identifierIsNormalizedAndSurroundingSpacesAreTolerated() {
        assertEquals(SELLER, GatewayIdentity.resolve(request(gateway, SELLER.toUpperCase(java.util.Locale.ROOT), "api-key"), KEY_ROUTE).sellerId());
        assertEquals(SELLER, GatewayIdentity.resolve(request(gateway, " " + SELLER + " ", "api-key"), KEY_ROUTE).sellerId());
    }
}
