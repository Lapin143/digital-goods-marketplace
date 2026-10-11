package dgm.platform.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.platform.identity.domain.Actor;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.domain.UserFilter;
import dgm.platform.identity.domain.UserPage;
import dgm.platform.identity.testing.FakeIdentityProvider;
import dgm.platform.identity.testing.FakeTransaction;
import dgm.platform.identity.testing.InMemoryAccounts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Операции администратора: роли (INV-36, INV-38), деактивация, анонимизация (INV-44), порядок «база и Outbox, потом Keycloak». */
class StaffUserServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID ADMIN = id(1);
    private static final UUID USER = id(2);
    private static final Actor ACTOR = new Actor(ADMIN, "admin", "203.0.113.7");

    private InMemoryAccounts accounts;
    private FakeTransaction tx;
    private FakeIdentityProvider keycloak;
    private StaffUserService service;

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-4000-8000-%012d", n));
    }

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccounts();
        tx = new FakeTransaction();
        keycloak = new FakeIdentityProvider(tx);
        service = new StaffUserService(accounts, keycloak, tx, new ManualClock(NOW));
        accounts.add(ADMIN, "admin@mail.example", "admin", NOW.minusSeconds(1000));
        accounts.add(USER, "user@mail.example", "buyer", NOW.minusSeconds(500));
        keycloak.add(FakeIdentityProvider.user(ADMIN, "admin@mail.example", null, null, true), "admin");
        keycloak.add(FakeIdentityProvider.user(USER, "user@mail.example", null, null, true), "buyer");
    }

    private static void assertProblem(ProblemType type, Runnable action) {
        assertEquals(type, assertThrows(ProblemException.class, action::run).type());
    }

    // ---- changeUserRole ----

    @Test
    void buyerBecomesModeratorSupportOperatorAndAdmin() {
        for (String role : List.of("moderator", "support-operator", "admin")) {
            UserAccount changed = service.changeRole(ACTOR, USER, role);
            assertEquals(role, changed.role());
            assertEquals(role, accounts.find(USER).orElseThrow().role());
        }
    }

    @Test
    void staffRoleCanBeChangedToAnotherStaffRoleAndBackToBuyer() {
        service.changeRole(ACTOR, USER, "moderator");
        assertEquals("support-operator", service.changeRole(ACTOR, USER, "support-operator").role());
        assertEquals("buyer", service.changeRole(ACTOR, USER, "buyer").role());
    }

    @Test
    void roleChangeWritesTheEventAndTheAuditRecordInOneTransaction() {
        service.changeRole(ACTOR, USER, "moderator");

        List<FakeTransaction.Event> events = tx.events();
        assertEquals(List.of("user.role-assigned", "audit.recorded"), events.stream().map(FakeTransaction.Event::type).toList());
        FakeTransaction.Event assigned = events.get(0);
        assertEquals("identity.events", assigned.topic());
        assertEquals(USER.toString(), assigned.aggregateId());
        assertEquals(Map.of("userId", USER.toString(), "role", "moderator", "source", "admin", "assignedBy", ADMIN.toString()), assigned.data());

        FakeTransaction.Event audit = events.get(1);
        assertEquals("audit.events", audit.topic());
        assertEquals("AuditRecord", audit.aggregateType());
        assertEquals(USER.toString(), audit.aggregateId());
        Map<String, Object> data = audit.data();
        assertEquals("user.role_assigned", data.get("action"));
        assertEquals(ADMIN.toString(), data.get("actorId"));
        assertEquals("admin", data.get("actorRole"));
        assertEquals("user", data.get("objectType"));
        assertEquals(USER.toString(), data.get("objectId"));
        assertEquals("203.0.113.7", data.get("actorIp"));
        assertEquals(Boolean.FALSE, data.get("actorIpHashed"));
        assertEquals("2026-10-11T12:00:00.000Z", data.get("occurredAt"));
        assertEquals(List.of(Map.of("field", "role", "personal", false, "before", "buyer", "after", "moderator")), data.get("changes"));
    }

    @Test
    void auditRecordFollowsTheClosedSchemaAndCarriesNoPersonalValues() {
        service.changeRole(ACTOR, USER, "moderator");

        Map<String, Object> data = tx.events("audit.recorded").get(0).data();
        assertEquals(Set.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes", "actorIp", "actorIpHashed",
                "correlationId"), data.keySet());
        assertFalse(data.toString().contains("user@mail.example"));
        assertFalse(data.toString().contains("admin@mail.example"));
        assertTrue(data.get("action").toString().matches("^[a-z]+(\\.[a-z_]+)+$"));
    }

    @Test
    void sameRoleAgainChangesNothingAndWritesNoEvents() {
        service.changeRole(ACTOR, USER, "moderator");
        int events = tx.events().size();

        UserAccount again = service.changeRole(ACTOR, USER, "moderator");

        assertEquals("moderator", again.role());
        assertEquals(events, tx.events().size());
    }

    @Test
    void keycloakIsAskedAfterTheCommitWithTheRoleAndTheTotpRequirement() {
        service.changeRole(ACTOR, USER, "moderator");

        assertEquals(List.of("applyRole moderator totp=true " + USER), keycloak.calls());
        assertEquals(Set.of("moderator"), keycloak.rolesOf(USER));
    }

    @Test
    void totpIsNotRequiredFromAUserWhoAlreadyHasASecondFactor() {
        accounts.updateHas2fa(USER, true);

        service.changeRole(ACTOR, USER, "moderator");

        assertEquals(List.of("applyRole moderator totp=false " + USER), keycloak.calls());
    }

    @Test
    void totpIsNotRequiredWhenTheRoleReturnsToBuyer() {
        service.changeRole(ACTOR, USER, "moderator");
        service.changeRole(ACTOR, USER, "buyer");

        assertEquals("applyRole buyer totp=false " + USER, keycloak.calls().get(1));
    }

    @Test
    void roleSellerCannotBeAssignedByHand() {
        assertProblem(ProblemType.ROLE_MANAGED_BY_SYSTEM, () -> service.changeRole(ACTOR, USER, "seller"));

        assertEquals("buyer", accounts.find(USER).orElseThrow().role());
        assertEquals(List.of(), tx.events());
        assertEquals(List.of(), keycloak.calls());
    }

    @Test
    void roleSellerCannotBeRemovedByHand() {
        UUID seller = id(3);
        accounts.add(seller, "seller@mail.example", "seller", NOW);

        assertProblem(ProblemType.ROLE_MANAGED_BY_SYSTEM, () -> service.changeRole(ACTOR, seller, "buyer"));
        assertProblem(ProblemType.ROLE_MANAGED_BY_SYSTEM, () -> service.changeRole(ACTOR, seller, "moderator"));

        assertEquals("seller", accounts.find(seller).orElseThrow().role());
    }

    @Test
    void deactivatedAccountKeepsItsRole() {
        service.deactivate(ACTOR, USER);

        assertProblem(ProblemType.STATE_CONFLICT, () -> service.changeRole(ACTOR, USER, "moderator"));
    }

    @Test
    void lastActiveAdministratorCannotLoseTheRole() {
        assertProblem(ProblemType.LAST_ADMIN_REQUIRED, () -> service.changeRole(ACTOR, ADMIN, "buyer"));

        assertEquals("admin", accounts.find(ADMIN).orElseThrow().role());
        assertEquals(List.of(), tx.events());
        assertEquals(List.of(), keycloak.calls());
    }

    @Test
    void anAdministratorMayStepDownWhenAnotherActiveAdministratorExists() {
        service.changeRole(ACTOR, USER, "admin");

        assertEquals("buyer", service.changeRole(new Actor(USER, "admin", "198.51.100.2"), ADMIN, "buyer").role());
        assertProblem(ProblemType.LAST_ADMIN_REQUIRED, () -> service.changeRole(ACTOR, USER, "moderator"));
    }

    @Test
    void unknownAccountIsNotFound() {
        assertProblem(ProblemType.NOT_FOUND, () -> service.changeRole(ACTOR, id(99), "moderator"));
    }

    @Test
    void keycloakOutageDoesNotFailTheChangeAndTheDatabaseRemainsTheModel() {
        keycloak.down(true);

        UserAccount changed = service.changeRole(ACTOR, USER, "moderator");

        assertEquals("moderator", changed.role());
        assertEquals(2, tx.events().size());
        assertEquals(Set.of("buyer"), keycloak.rolesOf(USER));
    }

    // ---- deactivateUser ----

    @Test
    void deactivationMarksTheAccountWritesEventsAndDisablesKeycloak() {
        UserAccount result = service.deactivate(ACTOR, USER);

        assertEquals("deactivated", result.status());
        assertEquals(Optional.of(NOW), result.deactivatedAt());
        assertEquals(List.of("user.deactivated", "audit.recorded"), tx.events().stream().map(FakeTransaction.Event::type).toList());
        assertEquals(Map.of("userId", USER.toString(), "deactivatedBy", ADMIN.toString()), tx.events("user.deactivated").get(0).data());
        assertEquals("user.deactivated", tx.events("audit.recorded").get(0).data().get("action"));
        assertEquals(List.of("disable " + USER), keycloak.calls());
        assertFalse(keycloak.enabled(USER));
    }

    @Test
    void repeatedDeactivationIsIdempotentAndRetriesKeycloak() {
        service.deactivate(ACTOR, USER);

        UserAccount again = service.deactivate(ACTOR, USER);

        assertEquals("deactivated", again.status());
        assertEquals(2, tx.events().size());
        assertEquals(List.of("disable " + USER, "disable " + USER), keycloak.calls());
    }

    @Test
    void lastActiveAdministratorCannotBeDeactivated() {
        assertProblem(ProblemType.LAST_ADMIN_REQUIRED, () -> service.deactivate(ACTOR, ADMIN));

        assertTrue(accounts.find(ADMIN).orElseThrow().active());
        assertEquals(List.of(), tx.events());
        assertEquals(List.of(), keycloak.calls());
    }

    @Test
    void deactivationOfAnUnknownAccountIsNotFound() {
        assertProblem(ProblemType.NOT_FOUND, () -> service.deactivate(ACTOR, id(99)));
    }

    @Test
    void keycloakOutageDuringDeactivationIsLeftToTheReconciler() {
        keycloak.down(true);

        assertEquals("deactivated", service.deactivate(ACTOR, USER).status());
        assertTrue(keycloak.enabled(USER));
    }

    // ---- anonymizeUser ----

    @Test
    void activeAccountCannotBeAnonymized() {
        assertProblem(ProblemType.STATE_CONFLICT, () -> service.anonymize(ACTOR, USER));

        assertFalse(accounts.find(USER).orElseThrow().anonymized());
        assertEquals(List.of(), tx.events());
    }

    @Test
    void anonymizationReplacesPersonalValuesWritesEventsAndDeletesTheKeycloakUser() {
        service.deactivate(ACTOR, USER);

        UserAccount result = service.anonymize(ACTOR, USER);

        assertTrue(result.anonymized());
        assertEquals("anonymized-" + USER + "@invalid", result.email());
        assertEquals("Удалён", result.name());
        assertEquals("deactivated", result.status());
        assertFalse(result.phoneConfirmed());
        FakeTransaction.Event event = tx.events("user.anonymized").get(0);
        assertEquals(Map.of("userId", USER.toString(), "requestedBy", "admin"), event.data());
        assertEquals("user.anonymized", tx.events("audit.recorded").get(1).data().get("action"));
        assertFalse(keycloak.exists(USER));
    }

    @Test
    void anonymizationEventsAndAuditCarryOnlyTheIdentifier() {
        service.deactivate(ACTOR, USER);
        service.anonymize(ACTOR, USER);

        for (FakeTransaction.Event event : tx.events()) {
            assertFalse(event.data().toString().contains("user@mail.example"), event.type());
        }
    }

    @Test
    void repeatedAnonymizationChangesNothingAndRetriesKeycloak() {
        service.deactivate(ACTOR, USER);
        service.anonymize(ACTOR, USER);
        int events = tx.events().size();

        UserAccount again = service.anonymize(ACTOR, USER);

        assertTrue(again.anonymized());
        assertEquals(events, tx.events().size());
        assertEquals("delete " + USER, keycloak.calls().get(keycloak.calls().size() - 1));
    }

    @Test
    void anonymizationOfAnUnknownAccountIsNotFound() {
        assertProblem(ProblemType.NOT_FOUND, () -> service.anonymize(ACTOR, id(99)));
    }

    // ---- listStaffUsers ----

    @Test
    void listIsNewestFirstWithAContinuationWhenThereAreMoreRows() {
        accounts.add(id(3), "c@mail.example", "moderator", NOW.minusSeconds(100));
        accounts.add(id(4), "d@mail.example", "moderator", NOW.minusSeconds(50));

        UserPage first = service.list(new UserFilter(Optional.empty(), Optional.empty(), Optional.empty(), 2, Optional.empty()));

        assertEquals(List.of(id(4), id(3)), first.items().stream().map(UserAccount::id).toList());
        UserFilter.After next = first.next().orElseThrow();
        UserPage second = service.list(new UserFilter(Optional.empty(), Optional.empty(), Optional.empty(), 2, Optional.of(next)));
        assertEquals(List.of(USER, ADMIN), second.items().stream().map(UserAccount::id).toList());
        assertTrue(second.next().isEmpty());
    }

    @Test
    void filtersByRoleStatusAndExactEmail() {
        accounts.add(id(3), "c@mail.example", "moderator", NOW.minusSeconds(100));
        service.deactivate(ACTOR, USER);

        assertEquals(List.of(id(3)), ids(new UserFilter(Optional.of("moderator"), Optional.empty(), Optional.empty(), 20, Optional.empty())));
        assertEquals(List.of(USER), ids(new UserFilter(Optional.empty(), Optional.of("deactivated"), Optional.empty(), 20, Optional.empty())));
        assertEquals(List.of(ADMIN), ids(new UserFilter(Optional.empty(), Optional.empty(), Optional.of("admin@mail.example"), 20, Optional.empty())));
        assertEquals(List.of(), ids(new UserFilter(Optional.of("moderator"), Optional.of("deactivated"), Optional.empty(), 20, Optional.empty())));
    }

    private List<UUID> ids(UserFilter filter) {
        return service.list(filter).items().stream().map(UserAccount::id).toList();
    }
}
