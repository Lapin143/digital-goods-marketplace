package dgm.platform.identity.otp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.platform.identity.otp.crypto.OtpCrypto;
import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.otp.testing.ClockedPacer;
import dgm.platform.identity.otp.testing.FakeParameters;
import dgm.platform.identity.otp.testing.InMemoryOtpStore;
import dgm.platform.identity.otp.testing.RecordingSms;
import dgm.platform.identity.otp.testing.ScriptedCodes;
import dgm.platform.identity.testing.FakeTransaction;
import dgm.platform.identity.testing.InMemoryAccounts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Вход по коду из SMS (US-1.7, FT-1.7): кому выпускается код, нейтральность и время ответа (ST-06, U12), событие входа. */
class SmsLoginServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID BUYER = id(1);
    private static final UUID SELLER = id(2);
    private static final UUID UNCONFIRMED = id(3);
    private static final UUID DEACTIVATED = id(4);
    private static final UUID MODERATOR = id(5);
    private static final PhoneNumber BUYER_PHONE = new PhoneNumber("+79000000001");
    private static final PhoneNumber SELLER_PHONE = new PhoneNumber("+79000000002");
    private static final PhoneNumber DEACTIVATED_PHONE = new PhoneNumber("+79000000004");
    private static final PhoneNumber MODERATOR_PHONE = new PhoneNumber("+79000000005");
    private static final PhoneNumber UNKNOWN = new PhoneNumber("+79000000099");
    private static final Optional<String> IP = Optional.of("203.0.113.5");

    private ManualClock clock;
    private InMemoryAccounts accounts;
    private FakeTransaction tx;
    private RecordingSms sms;
    private ScriptedCodes codes;
    private ClockedPacer pacer;
    private SmsLoginService service;

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-4000-8000-%012d", n));
    }

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        accounts = new InMemoryAccounts();
        tx = new FakeTransaction();
        sms = new RecordingSms();
        codes = new ScriptedCodes();
        pacer = new ClockedPacer(clock);
        InMemoryOtpStore store = new InMemoryOtpStore(clock);
        OtpService otp = new OtpService(store, OtpCrypto.ofBytes("test-pepper-0123456789abcdef-0123".getBytes(StandardCharsets.UTF_8)), codes,
                sms, new FakeParameters(), OtpProperties.defaults(), clock, new SimpleMeterRegistry());
        service = new SmsLoginService(accounts, tx, otp, pacer, OtpProperties.defaults(), clock);

        accounts.add(BUYER, "buyer@mail.example", "buyer", T0.minusSeconds(900));
        accounts.confirmPhone(BUYER, BUYER_PHONE.value());
        accounts.add(SELLER, "seller@mail.example", "seller", T0.minusSeconds(800));
        accounts.confirmPhone(SELLER, SELLER_PHONE.value());
        accounts.add(UNCONFIRMED, "new@mail.example", "buyer", T0.minusSeconds(700));
        accounts.add(DEACTIVATED, "gone@mail.example", "buyer", T0.minusSeconds(600));
        accounts.confirmPhone(DEACTIVATED, DEACTIVATED_PHONE.value());
        accounts.deactivate(DEACTIVATED, T0.minusSeconds(60));
        accounts.add(MODERATOR, "mod@mail.example", "moderator", T0.minusSeconds(500));
        accounts.confirmPhone(MODERATOR, MODERATOR_PHONE.value());
    }

    private static ProblemException problem(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    private Duration took(Runnable action) {
        Instant before = clock.instant();
        action.run();
        return Duration.between(before, clock.instant());
    }

    // ---- запрос кода ----

    @Test
    void codeIsSentOnlyToTheConfirmedPhoneOfAnActiveBuyer() {
        codes.then("123456");
        service.requestCode(BUYER_PHONE, IP);

        assertEquals(1, sms.count());
        assertEquals(BUYER_PHONE.value(), sms.last().orElseThrow().to());
    }

    @Test
    void numbersOfSellersStaffDeactivatedAndUnknownAccountsGetNoSms() {
        service.requestCode(SELLER_PHONE, IP);
        service.requestCode(MODERATOR_PHONE, Optional.of("203.0.113.6"));
        service.requestCode(DEACTIVATED_PHONE, Optional.of("203.0.113.7"));
        service.requestCode(UNKNOWN, Optional.of("203.0.113.8"));

        assertEquals(0, sms.count(), "SMS не уходит никому, кроме активного покупателя с подтверждённым номером");
    }

    @Test
    void responseTimeIsAlignedToTheMinimumForBothBranches() {
        Duration known = took(() -> service.requestCode(BUYER_PHONE, IP));
        Duration unknown = took(() -> service.requestCode(UNKNOWN, Optional.of("203.0.113.6")));
        Duration seller = took(() -> service.requestCode(SELLER_PHONE, Optional.of("203.0.113.7")));

        assertEquals(Duration.ofMillis(800), known);
        assertEquals(known, unknown, "по времени ответа нельзя отличить привязанный номер от неизвестного");
        assertEquals(known, seller);
        assertEquals(3, pacer.deadlines().size());
    }

    @Test
    void rejectedRequestIsNotPaced() {
        for (int i = 0; i < 3; i++) {
            service.requestCode(UNKNOWN, IP);
        }
        int paced = pacer.deadlines().size();

        ProblemException limited = problem(() -> service.requestCode(UNKNOWN, IP));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        assertEquals(paced, pacer.deadlines().size());
    }

    // ---- проверка кода ----

    @Test
    void correctCodeReturnsTheAccountAndWritesTheLoginEvent() {
        codes.then("123456");
        service.requestCode(BUYER_PHONE, IP);

        UUID user = service.verify(BUYER_PHONE, "123456");

        assertEquals(BUYER, user);
        assertEquals(1, tx.events().size());
        FakeTransaction.Event event = tx.events("user.sms-login").get(0);
        assertEquals("identity.events", event.topic());
        assertEquals("User", event.aggregateType());
        assertEquals(BUYER.toString(), event.aggregateId());
        assertEquals(Map.of("userId", BUYER.toString()), event.data(), "в событии нет ни номера, ни кода");
    }

    @Test
    void wrongCodeWritesNoEvent() {
        codes.then("123456");
        service.requestCode(BUYER_PHONE, IP);

        ProblemException wrong = problem(() -> service.verify(BUYER_PHONE, "000000"));

        assertEquals(ProblemType.OTP_INVALID, wrong.type());
        assertTrue(tx.events().isEmpty());
    }

    @Test
    void unknownNumberAnswersLikeAWrongCode() {
        service.requestCode(UNKNOWN, IP);

        ProblemException unknown = problem(() -> service.verify(UNKNOWN, "123456"));

        assertEquals(ProblemType.OTP_INVALID, unknown.type());
        assertEquals(Map.of(), unknown.extensions());
    }

    @Test
    void accountDeactivatedWhileTheCodeWasLiveCannotLogIn() {
        codes.then("123456");
        service.requestCode(BUYER_PHONE, IP);
        accounts.deactivate(BUYER, T0);

        ProblemException gone = problem(() -> service.verify(BUYER_PHONE, "123456"));

        assertEquals(ProblemType.OTP_INVALID, gone.type());
        assertTrue(tx.events().isEmpty());
    }

    @Test
    void roleChangedToSellerWhileTheCodeWasLiveCannotLogInBySms() {
        codes.then("123456");
        service.requestCode(BUYER_PHONE, IP);
        accounts.updateRole(BUYER, "seller");

        ProblemException closed = problem(() -> service.verify(BUYER_PHONE, "123456"));

        assertEquals(ProblemType.OTP_INVALID, closed.type());
        assertFalse(sms.sent().isEmpty());
    }

    @Test
    void customMinimumResponseTimeComesFromProperties() {
        OtpProperties slow = new OtpProperties(Duration.ofMinutes(5), 5, Duration.ofSeconds(2), Duration.ofMinutes(10), 3, Duration.ofHours(1),
                Duration.ofSeconds(60));
        OtpService otp = new OtpService(new InMemoryOtpStore(clock), OtpCrypto.ofBytes("test-pepper-0123456789abcdef-0123".getBytes(StandardCharsets.UTF_8)),
                codes, sms, new FakeParameters(), slow, clock, new SimpleMeterRegistry());
        SmsLoginService slowService = new SmsLoginService(accounts, tx, otp, pacer, slow, clock);

        Duration took = took(() -> slowService.requestCode(UNKNOWN, IP));

        assertEquals(Duration.ofSeconds(2), took);
    }
}
