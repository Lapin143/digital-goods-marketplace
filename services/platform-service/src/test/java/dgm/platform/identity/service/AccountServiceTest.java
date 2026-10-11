package dgm.platform.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.time.ManualClock;
import dgm.platform.identity.domain.UserAccount;
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

/** Создание учётной записи при первом обращении (ADR-025, пункт 1; решения Р-6, Р-7, Р-30). */
class AccountServiceTest {

    private static final UUID ID = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private InMemoryAccounts accounts;
    private FakeTransaction tx;
    private FakeIdentityProvider keycloak;
    private AccountService service;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccounts();
        tx = new FakeTransaction();
        keycloak = new FakeIdentityProvider(tx);
        service = new AccountService(accounts, keycloak, tx, new ManualClock(NOW));
    }

    private static AuthenticatedUser token(UUID id, String role, String... amr) {
        return new AuthenticatedUser(id.toString(), Set.of("orders.read"), Set.of(role, "default-roles-dgm"), List.of(amr), false);
    }

    @Test
    void firstRequestCreatesTheAccountFromKeycloakProfileAndTokenClaims() {
        keycloak.add(FakeIdentityProvider.user(ID, "Ivan@Mail.example", "Иван", "Петров", true), "buyer");

        service.ensureAccount(token(ID, "buyer", "pwd"));

        UserAccount account = accounts.find(ID).orElseThrow();
        assertEquals("ivan@mail.example", account.email());
        assertEquals("Иван Петров", account.name());
        assertEquals("buyer", account.role());
        assertTrue(account.active());
        assertFalse(account.has2fa());
        assertEquals(NOW, account.registeredAt());
        assertEquals(1, tx.events().size());
        FakeTransaction.Event event = tx.events().get(0);
        assertEquals("identity.events", event.topic());
        assertEquals("User", event.aggregateType());
        assertEquals(ID.toString(), event.aggregateId());
        assertEquals("user.registered", event.type());
        assertEquals(Map.of("userId", ID.toString(), "role", "buyer", "registrationMethod", "password"), event.data());
    }

    @Test
    void roleAndSecondFactorComeFromTheToken() {
        keycloak.add(FakeIdentityProvider.user(ID, "admin@mail.example", null, null, true), "admin");

        service.ensureAccount(token(ID, "admin", "pwd", "otp"));

        UserAccount account = accounts.find(ID).orElseThrow();
        assertEquals("admin", account.role());
        assertTrue(account.has2fa());
        assertEquals("admin", account.name());
    }

    @Test
    void personalDataNeverGoesIntoTheEvent() {
        keycloak.add(FakeIdentityProvider.user(ID, "ivan@mail.example", "Иван", "Петров", true), "buyer");

        service.ensureAccount(token(ID, "buyer", "pwd"));

        String event = tx.events().get(0).data().toString();
        assertFalse(event.contains("ivan@mail.example"));
        assertFalse(event.contains("Иван"));
    }

    @Test
    void vkIdRegistrationIsRecordedAsSuch() {
        keycloak.add(FakeIdentityProvider.user(ID, "ivan@mail.example", null, null, true), "buyer").withFederation(ID);

        service.ensureAccount(token(ID, "buyer", "vk"));

        assertEquals("vk_id", tx.events().get(0).data().get("registrationMethod"));
    }

    @Test
    void nextRequestsDoNotTouchKeycloakOrWriteAgain() {
        keycloak.add(FakeIdentityProvider.user(ID, "ivan@mail.example", null, null, true), "buyer");
        service.ensureAccount(token(ID, "buyer", "pwd"));
        int calls = keycloak.calls().size();

        service.ensureAccount(token(ID, "buyer", "pwd"));
        service.ensureAccount(token(ID, "buyer", "pwd"));

        assertEquals(calls, keycloak.calls().size());
        assertEquals(1, tx.events().size());
        assertEquals(1, accounts.size());
    }

    @Test
    void existingAccountIsNotRecreatedAndKeycloakIsNotAsked() {
        accounts.add(ID, "ivan@mail.example", "buyer", NOW);

        service.ensureAccount(token(ID, "buyer", "pwd"));

        assertEquals(List.of(), keycloak.calls());
        assertEquals(List.of(), tx.events());
    }

    @Test
    void lostRaceReturnsTheWinnersRecordWithoutASecondEvent() {
        keycloak.add(FakeIdentityProvider.user(ID, "ivan@mail.example", null, null, true), "buyer");
        // Соперник успевает вставить запись между чтением и вставкой; событие он записал сам
        accounts.beforeInsert(() -> accounts.add(ID, "ivan@mail.example", "buyer", NOW));

        service.ensureAccount(token(ID, "buyer", "pwd"));

        assertEquals(1, accounts.size());
        assertEquals(List.of(), tx.events());
    }

    @Test
    void addressOfAnotherAccountIsRefusedWithoutCreatingARecord() {
        accounts.add(UUID.fromString("0199e0a0-0000-4000-8000-0000000000b2"), "ivan@mail.example", "buyer", NOW);
        keycloak.add(FakeIdentityProvider.user(ID, "ivan@mail.example", null, null, true), "buyer");

        ProblemException e = assertThrows(ProblemException.class, () -> service.ensureAccount(token(ID, "buyer", "pwd")));

        assertEquals(ProblemType.EMAIL_NOT_ALLOWED, e.type());
        assertTrue(accounts.find(ID).isEmpty());
        assertEquals(List.of(), tx.events());
    }

    @Test
    void unknownUserOfTheTokenIsUnauthenticated() {
        ProblemException e = assertThrows(ProblemException.class, () -> service.ensureAccount(token(ID, "buyer", "pwd")));

        assertEquals(ProblemType.UNAUTHENTICATED, e.type());
        assertEquals(0, accounts.size());
    }

    @Test
    void unavailableKeycloakGivesRetryableDependencyError() {
        keycloak.down(true);

        ProblemException e = assertThrows(ProblemException.class, () -> service.ensureAccount(token(ID, "buyer", "pwd")));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals("5", e.headers().get("Retry-After"));
        assertEquals(0, accounts.size());
        // После восстановления Keycloak тот же запрос проходит
        keycloak.clearFailures();
        keycloak.add(FakeIdentityProvider.user(ID, "ivan@mail.example", null, null, true), "buyer");
        service.ensureAccount(token(ID, "buyer", "pwd"));
        assertEquals(1, accounts.size());
    }

    @Test
    void subjectThatIsNotAUuidIsUnauthenticated() {
        AuthenticatedUser odd = new AuthenticatedUser("not-a-uuid", Set.of(), Set.of("buyer"), List.of("pwd"), false);

        ProblemException e = assertThrows(ProblemException.class, () -> service.ensureAccount(odd));

        assertEquals(ProblemType.UNAUTHENTICATED, e.type());
    }
}
