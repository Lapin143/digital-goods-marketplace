package dgm.platform.identity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RolesTest {

    @Test
    void onlyThePlatformRolesAreKnown() {
        for (String role : List.of("buyer", "seller", "moderator", "support-operator", "admin")) {
            assertTrue(Roles.known(role), role);
        }
        assertFalse(Roles.known("guest"));
        assertFalse(Roles.known("default-roles-dgm"));
        assertFalse(Roles.known("Admin"));
    }

    @Test
    void staffRolesAreModeratorSupportOperatorAndAdmin() {
        assertTrue(Roles.staff(Roles.MODERATOR));
        assertTrue(Roles.staff(Roles.SUPPORT_OPERATOR));
        assertTrue(Roles.staff(Roles.ADMIN));
        assertFalse(Roles.staff(Roles.BUYER));
        assertFalse(Roles.staff(Roles.SELLER));
    }

    @Test
    void bestRolePicksTheMostPrivilegedAndIgnoresRealmServiceRoles() {
        assertEquals(Roles.BUYER, Roles.best(Set.of()));
        assertEquals(Roles.BUYER, Roles.best(Set.of("default-roles-dgm", "offline_access", "uma_authorization")));
        assertEquals(Roles.MODERATOR, Roles.best(Set.of("buyer", "moderator", "uma_authorization")));
        assertEquals(Roles.ADMIN, Roles.best(List.of("seller", "admin", "moderator")));
        assertEquals(Roles.SELLER, Roles.best(List.of("buyer", "seller")));
    }
}
