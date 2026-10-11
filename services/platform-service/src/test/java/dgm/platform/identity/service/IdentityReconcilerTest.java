package dgm.platform.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.keycloak.KeycloakException;
import dgm.kit.outbox.OutboxRelay;
import dgm.kit.time.ManualClock;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.testing.FakeIdentityProvider;
import dgm.platform.identity.testing.FakeTransaction;
import dgm.platform.identity.testing.InMemoryAccounts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Сверка с Keycloak (ADR-025, пункт 3): создаёт недостающее, возвращает Keycloak к записи платформы, повторяет недоставленное. */
class IdentityReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private InMemoryAccounts accounts;
    private FakeTransaction tx;
    private FakeIdentityProvider keycloak;
    private IdentityReconciler reconciler;

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-4000-8000-%012d", n));
    }

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccounts();
        tx = new FakeTransaction();
        keycloak = new FakeIdentityProvider(tx);
        AccountService created = new AccountService(accounts, keycloak, tx, new ManualClock(NOW));
        reconciler = new IdentityReconciler(accounts, keycloak, created, new StaticListableBeanFactory().getBeanProvider(OutboxRelay.class));
    }

    private void keycloakUser(int n, String role, boolean enabled) {
        keycloak.add(FakeIdentityProvider.user(id(n), "user" + n + "@mail.example", null, null, enabled), role);
    }

    @Test
    void userCreatedOutsideThePlatformGetsAnAccountWithTheKeycloakRoleAndSecondFactor() {
        keycloakUser(1, "moderator", true);
        keycloak.withOtp(id(1));
        keycloakUser(2, "buyer", true);

        IdentityReconciler.Summary summary = reconciler.runPass();

        assertEquals(2, summary.created());
        UserAccount moderator = accounts.find(id(1)).orElseThrow();
        assertEquals("moderator", moderator.role());
        assertTrue(moderator.has2fa());
        assertEquals("user1", moderator.name());
        assertEquals("buyer", accounts.find(id(2)).orElseThrow().role());
        assertEquals(List.of("user.registered", "user.registered"), tx.events().stream().map(FakeTransaction.Event::type).toList());
    }

    @Test
    void secondPassOnTheSameStateChangesNothing() {
        keycloakUser(1, "moderator", true);
        reconciler.runPass();
        int events = tx.events().size();

        IdentityReconciler.Summary summary = reconciler.runPass();

        assertEquals(new IdentityReconciler.Summary(1, 0, 0, 0, 0, 0, 0, 0), summary);
        assertEquals(events, tx.events().size());
    }

    @Test
    void keycloakIsBroughtBackToTheRoleOfThePlatform() {
        accounts.add(id(1), "user1@mail.example", "buyer", NOW);
        keycloakUser(1, "admin", true);

        IdentityReconciler.Summary summary = reconciler.runPass();

        assertEquals(1, summary.rolesFixed());
        assertTrue(keycloak.calls().contains("applyRole buyer totp=false " + id(1)));
        assertEquals(java.util.Set.of("buyer"), keycloak.rolesOf(id(1)));
    }

    @Test
    void lostRoleChangeIsRepeatedWithTheTotpRequirement() {
        accounts.add(id(1), "user1@mail.example", "moderator", NOW);
        keycloakUser(1, "buyer", true);

        reconciler.runPass();

        assertTrue(keycloak.calls().contains("applyRole moderator totp=true " + id(1)));
        assertEquals(java.util.Set.of("moderator"), keycloak.rolesOf(id(1)));
    }

    @Test
    void deactivatedAccountDisablesTheKeycloakUserAndNeverEnablesItBack() {
        UserAccount account = accounts.add(id(1), "user1@mail.example", "buyer", NOW);
        accounts.deactivate(account.id(), NOW);
        keycloakUser(1, "buyer", true);

        IdentityReconciler.Summary summary = reconciler.runPass();

        assertEquals(1, summary.disabled());
        assertFalse(keycloak.enabled(id(1)));
        // Пользователь отключён в Keycloak, запись активна: платформа его не включает
        accounts.add(id(2), "user2@mail.example", "buyer", NOW);
        keycloakUser(2, "buyer", false);
        reconciler.runPass();
        assertFalse(keycloak.enabled(id(2)));
        assertFalse(keycloak.calls().stream().anyMatch(call -> call.startsWith("enable")));
    }

    @Test
    void anonymizedAccountRemovesTheKeycloakUserThatSurvived() {
        accounts.add(id(1), "user1@mail.example", "buyer", NOW);
        accounts.deactivate(id(1), NOW);
        accounts.anonymize(id(1));
        keycloakUser(1, "buyer", false);

        IdentityReconciler.Summary summary = reconciler.runPass();

        assertEquals(1, summary.deleted());
        assertFalse(keycloak.exists(id(1)));
    }

    @Test
    void secondFactorFlagFollowsKeycloakCredentials() {
        accounts.add(id(1), "user1@mail.example", "moderator", NOW);
        keycloakUser(1, "moderator", true);
        keycloak.withOtp(id(1));

        IdentityReconciler.Summary summary = reconciler.runPass();

        assertEquals(1, summary.secondFactorUpdated());
        assertTrue(accounts.find(id(1)).orElseThrow().has2fa());
    }

    @Test
    void confirmedPhoneIsPassedToKeycloakUntilItIsMarkedAsSynced() {
        accounts.add(id(1), "user1@mail.example", "buyer", NOW);
        accounts.confirmPhone(id(1));
        keycloakUser(1, "buyer", true);

        IdentityReconciler.Summary first = reconciler.runPass();
        IdentityReconciler.Summary second = reconciler.runPass();

        assertEquals(1, first.phonesSynced());
        assertEquals(0, second.phonesSynced());
        assertTrue(keycloak.phoneVerified(id(1)));
        assertTrue(accounts.find(id(1)).orElseThrow().phoneSynced());
    }

    @Test
    void phoneStaysUnsyncedWhenKeycloakRefusesAndIsRetriedNextTime() {
        accounts.add(id(1), "user1@mail.example", "buyer", NOW);
        accounts.confirmPhone(id(1));
        keycloakUser(1, "buyer", true);
        keycloak.failFor(id(1));

        IdentityReconciler.Summary failed = reconciler.runPass();

        assertTrue(failed.failed() > 0);
        assertFalse(accounts.find(id(1)).orElseThrow().phoneSynced());
        keycloak.clearFailures();
        assertEquals(1, reconciler.runPass().phonesSynced());
    }

    @Test
    void failureForOneUserDoesNotStopTheOthers() {
        keycloakUser(1, "buyer", true);
        keycloakUser(2, "buyer", true);
        keycloakUser(3, "buyer", true);
        keycloak.failFor(id(2));

        IdentityReconciler.Summary summary = reconciler.runPass();

        assertEquals(2, summary.created());
        assertEquals(1, summary.failed());
        assertTrue(accounts.find(id(1)).isPresent());
        assertTrue(accounts.find(id(2)).isEmpty());
        assertTrue(accounts.find(id(3)).isPresent());
    }

    @Test
    void unavailableKeycloakStopsThePassSoThatTheNextOneRepeatsEverything() {
        keycloakUser(1, "buyer", true);
        keycloak.down(true);

        assertThrows(KeycloakException.class, reconciler::runPass);
        keycloak.clearFailures();
        assertEquals(1, reconciler.runPass().created());
    }

    @Test
    void usersAreReadPageByPageUntilAShortPage() {
        int users = IdentityReconciler.PAGE_SIZE * 2 + 30;
        for (int n = 1; n <= users; n++) {
            keycloakUser(n, "buyer", true);
        }

        IdentityReconciler.Summary summary = reconciler.runPass();

        assertEquals(users, summary.checked());
        assertEquals(users, summary.created());
        assertEquals(users, accounts.size());
        long pages = keycloak.calls().stream().filter(call -> call.startsWith("listUsers")).count();
        assertEquals(3, pages);
    }

    @Test
    void exactlyOneFullPageNeedsOneMoreReadToFindTheEnd() {
        for (int n = 1; n <= IdentityReconciler.PAGE_SIZE; n++) {
            keycloakUser(n, "buyer", true);
        }

        reconciler.runPass();

        assertEquals(2, keycloak.calls().stream().filter(call -> call.startsWith("listUsers")).count());
    }

    @Test
    void scheduledEntryRunsThePassWhenNoPublisherIsConfigured() {
        keycloakUser(1, "buyer", true);

        reconciler.scheduled();

        assertEquals(1, accounts.size());
    }

    @Test
    void scheduledEntrySurvivesAnUnavailableKeycloak() {
        keycloak.down(true);

        reconciler.scheduled();

        assertEquals(0, accounts.size());
    }
}
