package dgm.kit.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CurrentUserTest {

    private static AuthenticatedUser user(String subject) {
        return new AuthenticatedUser(subject, Set.of("orders.read"), Set.of("buyer"), List.of("pwd"), true);
    }

    @Test
    void returnsUserSetByJwtFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthenticatedUser user = user("0199e0a0-0000-4000-8000-0000000000a1");
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, user);
        assertSame(user, CurrentUser.of(request));
    }

    @Test
    void noUserMeansUnauthenticated() {
        ProblemException e = assertThrows(ProblemException.class, () -> CurrentUser.of(new MockHttpServletRequest()));
        assertEquals(ProblemType.UNAUTHENTICATED, e.type());
    }

    @Test
    void foreignAttributeIsNotTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, "some string");
        assertThrows(ProblemException.class, () -> CurrentUser.of(request));
    }

    @Test
    void idIsUuidFromSubject() {
        assertEquals(UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1"), CurrentUser.id(user("0199e0a0-0000-4000-8000-0000000000a1")));
    }

    @Test
    void subjectThatIsNotUuidIsUnauthenticated() {
        ProblemException e = assertThrows(ProblemException.class, () -> CurrentUser.id(user("alice")));
        assertEquals(ProblemType.UNAUTHENTICATED, e.type());
        assertThrows(ProblemException.class, () -> CurrentUser.id(user(null)));
    }

    @Test
    void sellerIdIsUuidFromClaim() {
        AuthenticatedUser seller = new AuthenticatedUser("0199e0a0-0000-4000-8000-0000000000a1", Set.of("seller.catalog"), Set.of("seller"),
                List.of("pwd", "otp"), true, "0199e0a0-0000-7000-8000-000000000101");
        assertEquals(UUID.fromString("0199e0a0-0000-7000-8000-000000000101"), CurrentUser.sellerId(seller));
    }

    @Test
    void missingOrBrokenSellerIdIsForbidden() {
        ProblemException none = assertThrows(ProblemException.class, () -> CurrentUser.sellerId(user("0199e0a0-0000-4000-8000-0000000000a1")));
        assertEquals(ProblemType.FORBIDDEN, none.type());
        AuthenticatedUser odd = new AuthenticatedUser("0199e0a0-0000-4000-8000-0000000000a1", Set.of(), Set.of("seller"), List.of("pwd"), true, "not-a-uuid");
        assertEquals(ProblemType.FORBIDDEN, assertThrows(ProblemException.class, () -> CurrentUser.sellerId(odd)).type());
    }

    @Test
    void emailComesFromTheTokenOrIsAValidationError() {
        AuthenticatedUser withEmail = new AuthenticatedUser("0199e0a0-0000-4000-8000-0000000000a1", Set.of("orders.write"), Set.of("buyer"),
                List.of("pwd"), true, null, "buyer@example.org");
        assertEquals("buyer@example.org", CurrentUser.email(withEmail));
        ProblemException none = assertThrows(ProblemException.class, () -> CurrentUser.email(user("0199e0a0-0000-4000-8000-0000000000a1")));
        assertEquals(ProblemType.VALIDATION_FAILED, none.type());
        AuthenticatedUser blank = new AuthenticatedUser("0199e0a0-0000-4000-8000-0000000000a1", Set.of(), Set.of("buyer"), List.of("pwd"), true,
                null, "  ");
        assertEquals(ProblemType.VALIDATION_FAILED, assertThrows(ProblemException.class, () -> CurrentUser.email(blank)).type());
    }
}
