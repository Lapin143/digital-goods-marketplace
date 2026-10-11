package dgm.platform.identity.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.kit.time.ManualClock;
import dgm.platform.identity.service.AccountService;
import dgm.platform.identity.testing.FakeIdentityProvider;
import dgm.platform.identity.testing.FakeTransaction;
import dgm.platform.identity.testing.InMemoryAccounts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Фильтр создаёт запись только для запросов с проверенным пользователем и сам отвечает ошибкой по RFC 9457, если запись создать нельзя. */
class AccountProvisioningFilterTest {

    private static final UUID ID = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");

    private InMemoryAccounts accounts;
    private FakeIdentityProvider keycloak;
    private AccountProvisioningFilter filter;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccounts();
        FakeTransaction tx = new FakeTransaction();
        keycloak = new FakeIdentityProvider(tx);
        filter = new AccountProvisioningFilter(new AccountService(accounts, keycloak, tx, new ManualClock(Instant.parse("2026-10-11T12:00:00Z"))));
    }

    private static MockHttpServletRequest withUser() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/staff/users");
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser(ID.toString(), Set.of("staff.admin"), Set.of("admin"),
                List.of("pwd", "otp"), false));
        return request;
    }

    @Test
    void requestWithAUserCreatesTheAccountAndContinues() throws Exception {
        keycloak.add(FakeIdentityProvider.user(ID, "admin@mail.example", null, null, true), "admin");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withUser(), new MockHttpServletResponse(), chain);

        assertEquals(1, accounts.size());
        assertNotNull(chain.getRequest());
    }

    @Test
    void requestWithoutAUserPassesUntouched() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/internal/v1/products/x"), new MockHttpServletResponse(), chain);

        assertEquals(0, accounts.size());
        assertEquals(List.of(), keycloak.calls());
        assertNotNull(chain.getRequest());
    }

    @Test
    void unavailableKeycloakStopsTheRequestWith503AndRetryAfter() throws Exception {
        keycloak.down(true);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(withUser(), response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        Map<String, Object> problem = Json.parseObject(response.getContentAsString());
        assertEquals("dependency-unavailable", problem.get("code"));
        assertNull(chain.getRequest());
    }

    @Test
    void userUnknownToKeycloakGets401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(withUser(), response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertEquals("unauthenticated", Json.parseObject(response.getContentAsString()).get("code"));
    }
}
