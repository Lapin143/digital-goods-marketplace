package dgm.platform.identity.otp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.otp.crypto.AuditHasher;
import dgm.platform.identity.otp.crypto.OtpCrypto;
import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.otp.testing.FakeParameters;
import dgm.platform.identity.otp.testing.InMemoryOtpStore;
import dgm.platform.identity.otp.testing.RecordingSms;
import dgm.platform.identity.otp.testing.ScriptedCodes;
import dgm.platform.identity.testing.FakeIdentityProvider;
import dgm.platform.identity.testing.FakeTransaction;
import dgm.platform.identity.testing.InMemoryAccounts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Подтверждение телефона покупателя (US-1.6, FT-1.5, INV-34): запрос кода и проверка с записью номера, события и аудита (ADR-014). */
class PhoneConfirmationServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");
    private static final PhoneNumber PHONE = new PhoneNumber("+79001234567");
    private static final Optional<String> IP = Optional.of("203.0.113.5");

    private ManualClock clock;
    private InMemoryAccounts accounts;
    private FakeTransaction tx;
    private FakeIdentityProvider keycloak;
    private RecordingSms sms;
    private ScriptedCodes codes;
    private InMemoryOtpStore store;
    private PhoneConfirmationService service;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        accounts = new InMemoryAccounts();
        tx = new FakeTransaction();
        keycloak = new FakeIdentityProvider(tx);
        store = new InMemoryOtpStore(clock);
        sms = new RecordingSms();
        codes = new ScriptedCodes();
        OtpService otp = new OtpService(store, OtpCrypto.ofBytes("test-pepper-0123456789abcdef-0123".getBytes(StandardCharsets.UTF_8)), codes,
                sms, new FakeParameters(), OtpProperties.defaults(), clock, new SimpleMeterRegistry());
        AuditHasher hasher = AuditHasher.ofBytes("test-audit-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        service = new PhoneConfirmationService(accounts, keycloak, tx, otp, hasher, clock);
        accounts.add(USER, "buyer@mail.example", "buyer", T0.minusSeconds(100));
    }

    private static ProblemException problem(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    private PhoneConfirmationService.Started start() {
        return service.start(USER, PHONE, IP);
    }

    // ---- запрос кода ----

    @Test
    void startSendsTheCodeAndKeepsThePhoneOutOfTheDatabaseUntilConfirmed() {
        codes.then("123456");

        PhoneConfirmationService.Started started = start();

        assertEquals(PHONE, started.phone());
        assertEquals(5, started.attempts());
        assertEquals(T0.plusSeconds(300), started.expiresAt());
        assertEquals(Optional.empty(), started.resendAt());
        assertEquals(1, sms.count());
        assertEquals(PHONE.value(), sms.last().orElseThrow().to());
        assertEquals(Optional.empty(), accounts.phoneOf(USER), "до проверки кода номера в базе нет");
        assertFalse(accounts.find(USER).orElseThrow().phoneConfirmed());
        assertTrue(tx.events().isEmpty());
    }

    @Test
    void accountWithConfirmedPhoneCannotStartAnotherConfirmation() {
        accounts.confirmPhone(USER, "+79990000001");

        ProblemException conflict = problem(this::start);

        assertEquals(ProblemType.STATE_CONFLICT, conflict.type());
        assertEquals(0, sms.count());
    }

    @Test
    void deactivatedAccountCannotStartConfirmation() {
        accounts.deactivate(USER, T0);

        assertEquals(ProblemType.STATE_CONFLICT, problem(this::start).type());
        assertEquals(0, sms.count());
    }

    @Test
    void phoneOfAnotherAccountIsRejectedWithoutReasonAndWithoutSms() {
        accounts.add(OTHER, "other@mail.example", "buyer", T0.minusSeconds(200));
        accounts.confirmPhone(OTHER, PHONE.value());

        ProblemException rejected = problem(this::start);

        assertEquals(ProblemType.PHONE_NOT_ALLOWED, rejected.type());
        assertEquals("Этот номер нельзя использовать.", rejected.getMessage(), "причина не раскрывается, номера в тексте нет");
        assertEquals(0, sms.count());
    }

    @Test
    void probingForTakenNumbersRunsIntoTheSameRequestLimit() {
        accounts.add(OTHER, "other@mail.example", "buyer", T0.minusSeconds(200));
        accounts.confirmPhone(OTHER, PHONE.value());
        for (int i = 0; i < 3; i++) {
            assertEquals(ProblemType.PHONE_NOT_ALLOWED, problem(this::start).type());
        }

        ProblemException limited = problem(this::start);

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type(), "перебор чужих номеров упирается в три запроса за 10 минут");
    }

    // ---- проверка кода ----

    @Test
    void successfulVerificationWritesPhoneEventAndHashedAuditInOneTransactionThenTellsKeycloak() {
        codes.then("123456");
        PhoneConfirmationService.Started started = start();

        PhoneNumber confirmed = service.verify(USER, "buyer", started.confirmationId(), "123456", IP);

        assertEquals(PHONE, confirmed);
        UserAccount account = accounts.find(USER).orElseThrow();
        assertTrue(account.phoneConfirmed());
        assertEquals(Optional.of(PHONE.value()), accounts.phoneOf(USER));

        List<FakeTransaction.Event> published = tx.events();
        assertEquals(2, published.size());
        FakeTransaction.Event event = tx.events("user.phone-confirmed").get(0);
        assertEquals("identity.events", event.topic());
        assertEquals("User", event.aggregateType());
        assertEquals(USER.toString(), event.aggregateId());
        assertEquals(1, event.schemaVersion());
        assertEquals(Map.of("userId", USER.toString()), event.data(), "в событии только идентификатор");

        FakeTransaction.Event audit = tx.events("audit.recorded").get(0);
        assertEquals("audit.events", audit.topic());
        Map<String, Object> data = audit.data();
        assertEquals(Set.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes", "actorIp", "actorIpHashed",
                "correlationId"), data.keySet());
        assertEquals("user.phone_confirmed", data.get("action"));
        assertEquals("buyer", data.get("actorRole"));
        assertEquals(USER.toString(), data.get("actorId"));
        assertEquals("user", data.get("objectType"));
        assertEquals(USER.toString(), data.get("objectId"));
        assertEquals(Boolean.TRUE, data.get("actorIpHashed"));
        assertTrue(((String) data.get("actorIp")).matches("[0-9a-f]{64}"));
        List<?> changes = (List<?>) data.get("changes");
        Map<?, ?> phoneChange = (Map<?, ?>) changes.get(0);
        assertEquals("phone", phoneChange.get("field"));
        assertEquals(Boolean.TRUE, phoneChange.get("personal"));
        assertNull(phoneChange.get("before"));
        assertTrue(((String) phoneChange.get("after")).matches("[0-9a-f]{64}"));
        Map<?, ?> flagChange = (Map<?, ?>) changes.get(1);
        assertEquals(Map.of("field", "phone_confirmed", "personal", false, "before", false, "after", true), flagChange);

        String everything = Json.write(Map.of("event", event.data(), "audit", audit.data()));
        assertFalse(everything.contains("79001234567"), "номера нет ни в событии, ни в аудите");
        assertFalse(everything.contains("203.0.113.5"), "адреса пользователя в аудите нет, только хеш");

        assertTrue(keycloak.phoneVerified(USER));
        assertEquals(List.of("markPhoneVerified " + USER), keycloak.calls());
        assertTrue(accounts.find(USER).orElseThrow().phoneSynced(), "после передачи в Keycloak признак передачи выставлен");
    }

    @Test
    void unknownClientAddressIsHashedLikeAnyOther() {
        codes.then("123456");
        PhoneConfirmationService.Started started = start();

        service.verify(USER, "buyer", started.confirmationId(), "123456", Optional.empty());

        Map<String, Object> data = tx.events("audit.recorded").get(0).data();
        assertEquals(Boolean.TRUE, data.get("actorIpHashed"));
        assertTrue(((String) data.get("actorIp")).matches("[0-9a-f]{64}"));
    }

    @Test
    void keycloakFailureDoesNotUndoTheConfirmationAndLeavesTheSyncFlagForReconciliation() {
        codes.then("123456");
        PhoneConfirmationService.Started started = start();
        keycloak.down(true);

        PhoneNumber confirmed = service.verify(USER, "buyer", started.confirmationId(), "123456", IP);

        assertEquals(PHONE, confirmed);
        UserAccount account = accounts.find(USER).orElseThrow();
        assertTrue(account.phoneConfirmed());
        assertFalse(account.phoneSynced());
        assertEquals(List.of(USER), accounts.unsyncedPhones(10), "сверка повторит передачу");
        assertEquals(2, tx.events().size(), "событие и аудит записаны, Keycloak их не отменяет");
    }

    @Test
    void wrongCodeLeavesTheAccountUntouchedAndReportsAttemptsLeft() {
        codes.then("123456");
        PhoneConfirmationService.Started started = start();

        ProblemException wrong = problem(() -> service.verify(USER, "buyer", started.confirmationId(), "000000", IP));

        assertEquals(ProblemType.OTP_INVALID, wrong.type());
        assertEquals(4, wrong.extensions().get("attemptsLeft"));
        assertFalse(accounts.find(USER).orElseThrow().phoneConfirmed());
        assertTrue(tx.events().isEmpty());
        assertTrue(keycloak.calls().isEmpty());
    }

    @Test
    void confirmationIdOfAnotherRequestIsNotFound() {
        codes.then("123456");
        start();

        ProblemException missing = problem(() -> service.verify(USER, "buyer", UUID.randomUUID(), "123456", IP));

        assertEquals(ProblemType.NOT_FOUND, missing.type());
    }

    @Test
    void losingTheRaceForThePhoneAnswers409WritesNothingAndSpendsTheCode() {
        codes.then("123456");
        PhoneConfirmationService.Started started = start();
        accounts.loseNextPhoneRace();

        ProblemException lost = problem(() -> service.verify(USER, "buyer", started.confirmationId(), "123456", IP));

        assertEquals(ProblemType.PHONE_NOT_ALLOWED, lost.type());
        assertFalse(accounts.find(USER).orElseThrow().phoneConfirmed());
        assertTrue(tx.events().isEmpty(), "откат: ни события, ни аудита");
        assertTrue(keycloak.calls().isEmpty());
        ProblemException again = problem(() -> service.verify(USER, "buyer", started.confirmationId(), "123456", IP));
        assertEquals(ProblemType.OTP_EXPIRED, again.type(), "погашенный код не воскресает");
    }

    @Test
    void numberTakenBetweenRequestAndVerificationIsRejected() {
        codes.then("123456");
        PhoneConfirmationService.Started started = start();
        accounts.add(OTHER, "other@mail.example", "buyer", T0.minusSeconds(200));
        accounts.confirmPhone(OTHER, PHONE.value());

        ProblemException taken = problem(() -> service.verify(USER, "buyer", started.confirmationId(), "123456", IP));

        assertEquals(ProblemType.PHONE_NOT_ALLOWED, taken.type());
        assertFalse(accounts.find(USER).orElseThrow().phoneConfirmed());
        assertTrue(tx.events().isEmpty());
    }

    @Test
    void repeatedVerificationAfterSuccessDoesNotChangeAnything() {
        codes.then("123456");
        PhoneConfirmationService.Started started = start();
        service.verify(USER, "buyer", started.confirmationId(), "123456", IP);
        int events = tx.events().size();

        ProblemException again = problem(() -> service.verify(USER, "buyer", started.confirmationId(), "123456", IP));

        assertEquals(ProblemType.OTP_EXPIRED, again.type());
        assertEquals(events, tx.events().size());
    }

    @Test
    void redisOutageStopsTheRequestBeforeAnySms() {
        store.down(true);

        ProblemException down = problem(this::start);

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, down.type());
        assertEquals(0, sms.count());
    }
}
