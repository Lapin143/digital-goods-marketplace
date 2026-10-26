package dgm.platform.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.platform.identity.otp.crypto.AuditHasher;
import dgm.platform.identity.testing.FakeIdentityProvider;
import dgm.platform.identity.testing.FakeTransaction;
import dgm.platform.identity.testing.InMemoryAccounts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Применение подтверждённой смены e-mail (SEQ-04, шаги 18 – 20, Р-169): Keycloak, запись учётной записи, прежний адрес на 7 суток, событие
 * {@code user.email-changed} и запись аудита с хешами адресов; повторы, падение между Keycloak и транзакцией, гонка за адрес, исполнитель записи.
 */
class AccountEmailChangerTest {

    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");
    private static final UUID TICKET = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID OPERATOR = UUID.fromString("0199e0a0-0000-4000-8000-0000000000c1");
    private static final String OLD = "anna@mail.example";
    private static final String NEW = "new-anna@mail.example";
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private InMemoryAccounts accounts;
    private FakeTransaction tx;
    private FakeIdentityProvider keycloak;
    private AuditHasher hasher;
    private ManualClock clock;
    private AccountEmailChanger changer;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccounts();
        tx = new FakeTransaction();
        keycloak = new FakeIdentityProvider(tx);
        hasher = AuditHasher.ofBytes(new byte[32]);
        clock = new ManualClock(NOW);
        changer = new AccountEmailChanger(accounts, keycloak, tx, hasher, clock);
        accounts.add(USER, OLD, "buyer", NOW.minusSeconds(86_400));
        keycloak.add(FakeIdentityProvider.user(USER, OLD, "Анна", "Иванова", true), "buyer");
    }

    private static long setEmailCalls(FakeIdentityProvider keycloak) {
        return keycloak.calls().stream().filter(call -> call.startsWith("setEmail")).count();
    }

    private static ProblemException refused(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    @Tag("TC-710")
    @Tag("TC-717")
    @Test
    void theChangeIsMadeInKeycloakAndTheAccountWithTheEventAndTheAuditRecordWithHashesOnly() {
        assertTrue(changer.apply(USER, NEW, TICKET, Optional.of(OPERATOR)));

        assertEquals(NEW, keycloak.emailOf(USER));
        assertTrue(keycloak.emailVerified(USER), "адрес проверен кодом из письма");
        assertEquals(NEW, accounts.find(USER).orElseThrow().email());
        assertEquals(OLD, accounts.storedPreviousEmail(USER).orElseThrow());
        assertEquals(OLD, accounts.previousEmail(USER, NOW.plusSeconds(1)).orElseThrow());
        assertTrue(accounts.previousEmail(USER, NOW.plus(AccountEmailChanger.PREVIOUS_KEPT)).isEmpty(), "срок хранения 7 суток");

        List<FakeTransaction.Event> changed = tx.events(IdentityEvents.EMAIL_CHANGED);
        assertEquals(1, changed.size());
        FakeTransaction.Event event = changed.get(0);
        assertEquals(IdentityEvents.USER_TOPIC, event.topic());
        assertEquals(USER.toString(), event.aggregateId());
        assertEquals(Map.of("userId", USER.toString(), "ticketId", TICKET.toString(), "operatorId", OPERATOR.toString()), event.data());

        List<FakeTransaction.Event> audit = tx.events(IdentityEvents.AUDIT_EVENT);
        assertEquals(1, audit.size());
        Map<String, Object> data = audit.get(0).data();
        assertEquals(IdentityEvents.AUDIT_TOPIC, audit.get(0).topic());
        assertEquals("support.email_change_completed", data.get("action"));
        assertEquals(OPERATOR.toString(), data.get("actorId"));
        assertEquals("support-operator", data.get("actorRole"));
        assertEquals("user", data.get("objectType"));
        assertEquals(USER.toString(), data.get("objectId"));
        assertEquals("", data.get("actorIp"));
        assertEquals(Boolean.FALSE, data.get("actorIpHashed"));

        byte[] salt = accounts.auditSalt(USER).orElseThrow();
        List<?> changes = (List<?>) data.get("changes");
        assertEquals(2, changes.size());
        Map<?, ?> email = (Map<?, ?>) changes.get(0);
        assertEquals("email", email.get("field"));
        assertEquals(Boolean.TRUE, email.get("personal"));
        assertEquals(hasher.hash(salt, "email", OLD), email.get("before"));
        assertEquals(hasher.hash(salt, "email", NEW), email.get("after"));
        assertNotEquals(email.get("before"), email.get("after"));
        Map<?, ?> ticket = (Map<?, ?>) changes.get(1);
        assertEquals("ticketId", ticket.get("field"));
        assertEquals(TICKET.toString(), ticket.get("after"));

        String everything = event.data().toString() + data;
        assertFalse(everything.contains(OLD), "прежнего адреса в событии и аудите нет");
        assertFalse(everything.contains(NEW), "нового адреса в событии и аудите нет");
        assertTrue(tx.failedAfterCommit().isEmpty());
    }

    @Test
    void keycloakIsCalledBeforeTheTransactionAndNeverInsideIt() {
        changer.apply(USER, NEW, TICKET, Optional.of(OPERATOR));

        // Фейк Keycloak бросает AssertionError при вызове внутри транзакции; порядок: сначала Keycloak, события появляются после
        assertEquals(1, setEmailCalls(keycloak));
        assertEquals(2, tx.events().size());
    }

    @Tag("TC-710")
    @Test
    void aRepeatedCallChangesNothingAndDoesNotRepeatTheEventOrTheKeycloakCall() {
        assertTrue(changer.apply(USER, NEW, TICKET, Optional.of(OPERATOR)));
        assertFalse(changer.apply(USER, NEW, TICKET, Optional.of(OPERATOR)));

        assertEquals(1, tx.events(IdentityEvents.EMAIL_CHANGED).size());
        assertEquals(1, tx.events(IdentityEvents.AUDIT_EVENT).size());
        assertEquals(1, setEmailCalls(keycloak));
        assertEquals(OLD, accounts.storedPreviousEmail(USER).orElseThrow(), "прежний адрес не затирается повтором");
    }

    @Tag("TC-710")
    @Test
    void aCrashBetweenKeycloakAndTheTransactionIsFinishedByTheRetryWithTheSameValueAndOneEvent() {
        // Первый вызов дошёл до Keycloak и упал до фиксации в базе
        keycloak.setEmail(USER, NEW);
        assertEquals(OLD, accounts.find(USER).orElseThrow().email());
        assertTrue(tx.events().isEmpty());

        assertTrue(changer.apply(USER, NEW, TICKET, Optional.of(OPERATOR)));

        assertEquals(NEW, keycloak.emailOf(USER));
        assertEquals(NEW, accounts.find(USER).orElseThrow().email());
        assertEquals(2, setEmailCalls(keycloak), "тот же вызов с тем же значением");
        assertEquals(1, tx.events(IdentityEvents.EMAIL_CHANGED).size());
        assertEquals(1, tx.events(IdentityEvents.AUDIT_EVENT).size());
    }

    @Test
    void keycloakDownLeavesEverythingAsItWasAndAnswers503WithRetryAfter() {
        keycloak.down(true);

        ProblemException e = refused(() -> changer.apply(USER, NEW, TICKET, Optional.of(OPERATOR)));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals("30", e.headers().get("Retry-After"));
        assertEquals(OLD, accounts.find(USER).orElseThrow().email());
        assertTrue(accounts.storedPreviousEmail(USER).isEmpty());
        assertTrue(tx.events().isEmpty());
        assertFalse(e.getMessage().contains(NEW));
    }

    @Test
    void anAddressOfAnotherKeycloakUserIsNotAllowedAndNothingIsWritten() {
        keycloak.takeEmail(NEW);

        ProblemException e = refused(() -> changer.apply(USER, NEW, TICKET, Optional.of(OPERATOR)));

        assertEquals(ProblemType.EMAIL_NOT_ALLOWED, e.type());
        assertEquals(OLD, accounts.find(USER).orElseThrow().email());
        assertEquals(OLD, keycloak.emailOf(USER));
        assertTrue(tx.events().isEmpty());
    }

    @Test
    void anAddressOfAnotherAccountIsNotAllowedBeforeKeycloakIsCalled() {
        accounts.add(OTHER, NEW, "buyer", NOW.minusSeconds(3600));

        ProblemException e = refused(() -> changer.apply(USER, NEW, TICKET, Optional.of(OPERATOR)));

        assertEquals(ProblemType.EMAIL_NOT_ALLOWED, e.type());
        assertEquals(0, setEmailCalls(keycloak), "Keycloak с чужим адресом не трогается");
        assertTrue(tx.events().isEmpty());
    }

    @Tag("TC-710")
    @Test
    void anAddressTakenBetweenTheCheckAndTheTransactionIsNotAllowedAndKeycloakGoesBackToThePreviousAddress() {
        accounts.loseNextEmailRace();

        ProblemException e = refused(() -> changer.apply(USER, NEW, TICKET, Optional.of(OPERATOR)));

        assertEquals(ProblemType.EMAIL_NOT_ALLOWED, e.type());
        assertEquals(OLD, keycloak.emailOf(USER), "Keycloak возвращён к прежнему адресу");
        assertEquals(OLD, accounts.find(USER).orElseThrow().email());
        assertEquals(2, setEmailCalls(keycloak));
        assertTrue(tx.events().isEmpty(), "событие и аудит откатились вместе с записью");
    }

    @Test
    void aDeactivatedOrUnknownAccountIsAStateConflict() {
        accounts.deactivate(USER, NOW.minusSeconds(60));
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> changer.apply(USER, NEW, TICKET, Optional.of(OPERATOR))).type());
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> changer.apply(UUID.randomUUID(), NEW, TICKET, Optional.of(OPERATOR))).type());
        assertEquals(0, setEmailCalls(keycloak));
    }

    @Test
    void whenTheTicketHasNoOperatorAnymoreTheActorIsTheSystem() {
        changer.apply(USER, NEW, TICKET, Optional.empty());

        Map<String, Object> data = tx.events(IdentityEvents.AUDIT_EVENT).get(0).data();
        assertEquals("system", data.get("actorRole"));
        assertEquals(new UUID(0L, 0L).toString(), data.get("actorId"));
        assertEquals(new UUID(0L, 0L).toString(), tx.events(IdentityEvents.EMAIL_CHANGED).get(0).data().get("operatorId"));
    }
}
