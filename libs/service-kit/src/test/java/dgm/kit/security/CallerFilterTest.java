package dgm.kit.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.route.RouteRule;
import dgm.kit.testing.TestCertificates;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Внутренние вызовы: имя в клиентском сертификате должно быть в списке маршрута. */
class CallerFilterTest {

    private static final RouteRule INTERNAL = new RouteRule("GET", "/internal/v1/products/{id}", "getProductCard", List.of(), List.of(), false,
            List.of("order-service"));
    private static final RouteRule USER_ROUTE = new RouteRule("GET", "/api/v1/orders", "listOrders", List.of("orders.read"), List.of("buyer"), false,
            List.of());

    private static X509Certificate orderService;
    private static X509Certificate paymentService;

    @BeforeAll
    static void certificates() {
        orderService = TestCertificates.selfSigned("order-service").certificate();
        paymentService = TestCertificates.selfSigned("payment-service").certificate();
    }

    private record Result(MockHttpServletRequest request, MockHttpServletResponse response, MockFilterChain chain) {
        boolean passed() {
            return chain.getRequest() != null;
        }
    }

    private static Result call(RouteRule route, Object certificates) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/products/1");
        request.setAttribute(JwtFilter.ROUTE_ATTRIBUTE, route);
        if (certificates != null) {
            request.setAttribute(CallerFilter.CERTIFICATE_ATTRIBUTE, certificates);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new CallerFilter().doFilter(request, response, chain);
        return new Result(request, response, chain);
    }

    @Test
    void callerFromListPasses() throws Exception {
        Result r = call(INTERNAL, new X509Certificate[] {orderService});
        assertTrue(r.passed());
        assertEquals("order-service", r.request().getAttribute(CallerFilter.CALLER_ATTRIBUTE));
    }

    @Test
    void callerNotInListIsForbidden() throws Exception {
        Result r = call(INTERNAL, new X509Certificate[] {paymentService});
        assertEquals(403, r.response().getStatus());
        assertEquals("forbidden", Json.parseObject(r.response().getContentAsString()).get("code"));
        assertTrue(!r.passed());
    }

    @Test
    void noCertificateIsUnauthenticated() throws Exception {
        assertEquals(401, call(INTERNAL, null).response().getStatus());
        assertEquals(401, call(INTERNAL, new X509Certificate[0]).response().getStatus());
        assertEquals(401, call(INTERNAL, "not-a-certificate").response().getStatus());
    }

    @Test
    void routesForUsersAreNotChecked() throws Exception {
        assertTrue(call(USER_ROUTE, null).passed());
        assertTrue(call(USER_ROUTE, new X509Certificate[] {paymentService}).passed());
    }

    @Test
    void requestWithoutRouteIsNotChecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockFilterChain chain = new MockFilterChain();
        new CallerFilter().doFilter(request, new MockHttpServletResponse(), chain);
        assertTrue(chain.getRequest() != null);
    }

    @Test
    void commonNameIsExtracted() {
        assertEquals(Optional.of("order-service"), CallerFilter.commonName(orderService));
        assertEquals(Optional.empty(), CallerFilter.callerName(null));
    }
}
