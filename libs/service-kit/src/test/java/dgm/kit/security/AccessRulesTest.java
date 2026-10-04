package dgm.kit.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemType;
import dgm.kit.route.RouteRule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AccessRulesTest {

    private static final RouteRule BUYER_ROUTE = new RouteRule("GET", "/a", "op", List.of("orders.read"), List.of("buyer", "admin"), false, List.of());
    private static final RouteRule SELLER_ROUTE = new RouteRule("GET", "/s", "op", List.of("products.write"), List.of("seller"), false, List.of());
    private static final RouteRule NO_SMS_ROUTE = new RouteRule("POST", "/p", "op", List.of("orders.write"), List.of("buyer"), true, List.of());

    private static AuthenticatedUser user(String role, List<String> amr) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("u-1")
                .claim("scope", "orders.read orders.write products.write")
                .claim("realm_access", Map.of("roles", List.of(role)))
                .claim("amr", amr)
                .claim("phone_verified", true)
                .issuedAt(Instant.parse("2026-10-04T10:00:00Z")).expiresAt(Instant.parse("2026-10-04T10:05:00Z"))
                .build();
        return AuthenticatedUser.from(jwt);
    }

    @Test
    void allowedRoleWithoutSecondFactorPasses() {
        assertEquals(Optional.empty(), AccessRules.check(user("buyer", List.of("pwd")), BUYER_ROUTE));
    }

    @Test
    void roleNotInRouteListIsForbidden() {
        AccessRules.Denial d = AccessRules.check(user("buyer", List.of("pwd")), SELLER_ROUTE).orElseThrow();
        assertEquals(ProblemType.FORBIDDEN, d.type());
    }

    @Test
    void sellerWithoutOtpNeedsSecondFactor() {
        AccessRules.Denial d = AccessRules.check(user("seller", List.of("pwd")), SELLER_ROUTE).orElseThrow();
        assertEquals(ProblemType.SECOND_FACTOR_REQUIRED, d.type());
        assertEquals(Optional.empty(), AccessRules.check(user("seller", List.of("pwd", "otp")), SELLER_ROUTE));
    }

    @Test
    void everyStaffRoleNeedsSecondFactor() {
        for (String role : List.of("seller", "moderator", "support-operator", "admin")) {
            RouteRule rule = new RouteRule("GET", "/x", "op", List.of("orders.read"), List.of(role), false, List.of());
            assertEquals(ProblemType.SECOND_FACTOR_REQUIRED, AccessRules.check(user(role, List.of("pwd")), rule).orElseThrow().type(), role);
        }
    }

    @Test
    void smsOnlySessionIsDeniedOnProtectedAction() {
        AccessRules.Denial d = AccessRules.check(user("buyer", List.of("sms")), NO_SMS_ROUTE).orElseThrow();
        assertEquals(ProblemType.FULL_LOGIN_REQUIRED, d.type());
        assertEquals(Optional.empty(), AccessRules.check(user("buyer", List.of("pwd")), NO_SMS_ROUTE));
        assertEquals(Optional.empty(), AccessRules.check(user("buyer", List.of("vk")), NO_SMS_ROUTE));
        assertEquals(Optional.empty(), AccessRules.check(user("buyer", List.of("sms")), BUYER_ROUTE));
    }

    @Test
    void userFromTokenReadsClaims() {
        AuthenticatedUser u = user("seller", List.of("pwd", "otp"));
        assertEquals("u-1", u.subject());
        assertTrue(u.hasScope("orders.read"));
        assertTrue(u.hasRole("seller"));
        assertTrue(u.secondFactorPassed());
        assertTrue(u.phoneVerified());
        assertEquals(false, u.smsSession());
    }

    @Test
    void tokenWithoutOptionalClaimsGivesEmptySets() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("u-2")
                .issuedAt(Instant.parse("2026-10-04T10:00:00Z")).expiresAt(Instant.parse("2026-10-04T10:05:00Z")).build();
        AuthenticatedUser u = AuthenticatedUser.from(jwt);
        assertTrue(u.scopes().isEmpty());
        assertTrue(u.roles().isEmpty());
        assertTrue(u.amr().isEmpty());
        assertEquals(false, u.phoneVerified());
    }
}
