package dgm.platform.identity.otp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.platform.identity.otp.crypto.OtpCrypto;
import dgm.platform.identity.otp.domain.OtpLimits;
import dgm.platform.identity.otp.domain.OtpPurpose;
import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.otp.testing.FakeParameters;
import dgm.platform.identity.otp.testing.InMemoryOtpStore;
import dgm.platform.identity.otp.testing.RecordingSms;
import dgm.platform.identity.otp.testing.ScriptedCodes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Одноразовые коды без Redis и без ожидания: правила кода (NFT-3.7), лимиты запросов и потолок SMS (ST-11), нейтральность входа по SMS
 * (ST-06), отказ при недоступном Redis. Хранилище в памяти повторяет скрипты Lua, сами скрипты проверяет {@code RedisOtpStoreIT}.
 */
class OtpServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");
    private static final PhoneNumber PHONE = new PhoneNumber("+79001234567");
    private static final Optional<String> IP = Optional.of("203.0.113.5");

    private ManualClock clock;
    private InMemoryOtpStore store;
    private RecordingSms sms;
    private ScriptedCodes codes;
    private FakeParameters parameters;
    private OtpCrypto crypto;
    private OtpService otp;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        store = new InMemoryOtpStore(clock);
        sms = new RecordingSms();
        codes = new ScriptedCodes();
        parameters = new FakeParameters();
        crypto = OtpCrypto.ofBytes("test-pepper-0123456789abcdef-0123".getBytes(StandardCharsets.UTF_8));
        otp = new OtpService(store, crypto, codes, sms, parameters, OtpProperties.defaults(), clock, new SimpleMeterRegistry());
    }

    private OtpService.Issued issue(UUID user, PhoneNumber phone, Optional<String> ip) {
        OtpStore.Admission admission = otp.admitConfirmation(user, phone, ip);
        return otp.issueConfirmation(user, phone, admission);
    }

    private static ProblemException problem(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    private static PhoneNumber phone(int n) {
        return new PhoneNumber(String.format("+7900%07d", n));
    }

    // ---- код: вид, хранение, срок, попытки, повтор (NFT-3.7) ----

    @Test
    void codeHasSixDigitsGoesBySmsAndIsStoredOnlyAsKeyedHash() {
        codes.then("012345");

        OtpService.Issued issued = issue(USER, PHONE, IP);

        assertEquals(1, sms.count());
        assertEquals("012345", sms.last().orElseThrow().code());
        assertEquals(PHONE.value(), sms.last().orElseThrow().to());
        assertEquals(T0.plusSeconds(300), issued.expiresAt());
        assertEquals(5, issued.attempts());
        OtpStore.Code stored = store.code("code:phone_confirm:").orElseThrow();
        assertEquals(crypto.codeHash(OtpPurpose.PHONE_CONFIRM, USER.toString(), "012345"), stored.hash());
        assertTrue(stored.hash().matches("[0-9a-f]{64}"));
        assertNotEquals("012345", stored.hash());
        assertEquals(PHONE.value(), stored.phone());
        assertFalse(stored.toString().contains("012345"), "в строковом виде записи кода нет");
        for (String key : store.keys()) {
            assertFalse(key.contains("79001234567"), "номер в ключе Redis: " + key);
            assertFalse(key.contains("203.0.113.5"), "адрес в ключе Redis: " + key);
        }
    }

    @Test
    void hashDependsOnPurposeAndTargetSoCodesDoNotCross() {
        String a = crypto.codeHash(OtpPurpose.PHONE_CONFIRM, USER.toString(), "123456");
        assertNotEquals(a, crypto.codeHash(OtpPurpose.SMS_LOGIN, USER.toString(), "123456"));
        assertNotEquals(a, crypto.codeHash(OtpPurpose.PHONE_CONFIRM, OTHER.toString(), "123456"));
        assertEquals(a, crypto.codeHash(OtpPurpose.PHONE_CONFIRM, USER.toString(), "123456"));
    }

    @Test
    void correctCodeConfirmsAndReturnsThePhoneThatWaitedForConfirmation() {
        codes.then("123456");
        OtpService.Issued issued = issue(USER, PHONE, IP);

        PhoneNumber confirmed = otp.verifyConfirmation(USER, issued.confirmationId(), "123456");

        assertEquals(PHONE, confirmed);
    }

    @Test
    void codeIsValidUpToFiveMinutesAndExpiredAfterFiveMinutesOneSecond() {
        codes.then("111111", "222222");
        OtpService.Issued first = issue(USER, PHONE, IP);
        clock.advance(Duration.ofMinutes(5));
        assertEquals(PHONE, otp.verifyConfirmation(USER, first.confirmationId(), "111111"), "ровно через пять минут код ещё действует");

        clock.advance(Duration.ofMinutes(10));
        OtpService.Issued second = issue(USER, PHONE, IP);
        clock.advance(Duration.ofSeconds(5 * 60 + 1));
        ProblemException expired = problem(() -> otp.verifyConfirmation(USER, second.confirmationId(), "222222"));

        assertEquals(ProblemType.OTP_EXPIRED, expired.type());
    }

    @Test
    void fifthWrongAttemptLocksAndSixthIsRejectedEvenWithTheRightCode() {
        codes.then("111111");
        OtpService.Issued issued = issue(USER, PHONE, IP);

        for (int left = 4; left >= 1; left--) {
            ProblemException wrong = problem(() -> otp.verifyConfirmation(USER, issued.confirmationId(), "000000"));
            assertEquals(ProblemType.OTP_INVALID, wrong.type());
            assertEquals(left, wrong.extensions().get("attemptsLeft"));
        }
        ProblemException locked = problem(() -> otp.verifyConfirmation(USER, issued.confirmationId(), "000000"));
        assertEquals(ProblemType.OTP_LOCKED, locked.type());
        assertTrue(Integer.parseInt(locked.headers().get("Retry-After")) > 0);

        ProblemException sixth = problem(() -> otp.verifyConfirmation(USER, issued.confirmationId(), "111111"));
        assertEquals(ProblemType.OTP_EXPIRED, sixth.type(), "код погашен исчерпанием попыток, верный ввод его не воскрешает");
    }

    @Test
    void usedCodeCannotBeUsedAgain() {
        codes.then("123456");
        OtpService.Issued issued = issue(USER, PHONE, IP);
        otp.verifyConfirmation(USER, issued.confirmationId(), "123456");

        ProblemException again = problem(() -> otp.verifyConfirmation(USER, issued.confirmationId(), "123456"));

        assertEquals(ProblemType.OTP_EXPIRED, again.type());
    }

    @Test
    void newRequestRevokesThePreviousCode() {
        codes.then("111111", "222222");
        OtpService.Issued first = issue(USER, PHONE, IP);
        OtpService.Issued second = issue(USER, PHONE, IP);

        ProblemException old = problem(() -> otp.verifyConfirmation(USER, first.confirmationId(), "111111"));
        assertEquals(ProblemType.NOT_FOUND, old.type(), "запрос подтверждения устарел: идентификатор принадлежит новому");
        assertEquals(PHONE, otp.verifyConfirmation(USER, second.confirmationId(), "222222"));
    }

    @Test
    void codeOfAnotherUserDoesNotMatch() {
        codes.then("123456");
        OtpService.Issued issued = issue(USER, PHONE, IP);

        ProblemException other = problem(() -> otp.verifyConfirmation(OTHER, issued.confirmationId(), "123456"));

        assertEquals(ProblemType.OTP_EXPIRED, other.type());
        assertEquals(PHONE, otp.verifyConfirmation(USER, issued.confirmationId(), "123456"), "чужая попытка код владельца не тронула");
    }

    @Test
    void smsFailureRemovesTheCodeAndAnswers503() {
        codes.then("123456");
        sms.failing(true);

        ProblemException failure = problem(() -> issue(USER, PHONE, IP));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, failure.type());
        assertTrue(failure.headers().containsKey("Retry-After"));
        assertTrue(store.codes().isEmpty(), "код, который некому вручить, не остаётся в Redis");
    }

    // ---- лимиты запросов и потолок SMS (ST-05, ST-11) ----

    @Test
    void fourthRequestInTenMinutesIsRejectedWithRetryAfterAndFreesUpLater() {
        for (int i = 0; i < 3; i++) {
            issue(USER, PHONE, IP);
            clock.advance(Duration.ofSeconds(30));
        }
        int sentBefore = sms.count();

        ProblemException limited = problem(() -> otp.admitConfirmation(USER, PHONE, IP));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        assertEquals(sentBefore, sms.count(), "отказанный запрос SMS не отправляет");
        long retry = Long.parseLong(limited.headers().get("Retry-After"));
        assertEquals(510, retry, "первый запрос освободит окно через 10 минут минус прошедшие 90 секунд");
        assertEquals(limited.extensions().get("retryAfterAt"), dgm.kit.time.Timestamps.format(clock.instant().plusSeconds(retry)));

        clock.advance(Duration.ofSeconds(retry));
        assertEquals(PHONE, issueAndConfirm("333333"), "после ожидания запрос снова допускается");
    }

    private PhoneNumber issueAndConfirm(String code) {
        codes.then(code);
        OtpService.Issued issued = issue(USER, PHONE, IP);
        return otp.verifyConfirmation(USER, issued.confirmationId(), code);
    }

    @Test
    void rejectedRequestsDoNotExtendTheirOwnBlock() {
        for (int i = 0; i < 3; i++) {
            issue(USER, PHONE, IP);
        }
        long first = Long.parseLong(problem(() -> otp.admitConfirmation(USER, PHONE, IP)).headers().get("Retry-After"));
        clock.advance(Duration.ofSeconds(100));
        for (int i = 0; i < 10; i++) {
            problem(() -> otp.admitConfirmation(USER, PHONE, IP));
        }

        long later = Long.parseLong(problem(() -> otp.admitConfirmation(USER, PHONE, IP)).headers().get("Retry-After"));

        assertEquals(first - 100, later, "настойчивые повторы блокировку не продлевают");
    }

    @Test
    void tenRequestsPerDayPerAccountThenRejected() {
        for (int i = 0; i < 10; i++) {
            issue(USER, PHONE, IP);
            clock.advance(Duration.ofMinutes(11));
        }

        ProblemException limited = problem(() -> otp.admitConfirmation(USER, PHONE, IP));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        assertTrue(Long.parseLong(limited.headers().get("Retry-After")) > 3600, "суточное окно освободится не скоро");
    }

    @Test
    void phoneLimitIsSharedByAccounts() {
        issue(USER, PHONE, Optional.of("203.0.113.1"));
        issue(OTHER, PHONE, Optional.of("203.0.113.2"));
        issue(UUID.randomUUID(), PHONE, Optional.of("203.0.113.3"));

        ProblemException limited = problem(() -> otp.admitConfirmation(UUID.randomUUID(), PHONE, Optional.of("203.0.113.4")));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
    }

    @Test
    void ipLimitIsTenPerHourAcrossAccountsAndPhones() {
        for (int i = 1; i <= 10; i++) {
            issue(UUID.randomUUID(), phone(i), IP);
        }

        ProblemException limited = problem(() -> otp.admitConfirmation(UUID.randomUUID(), phone(11), IP));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        issue(UUID.randomUUID(), phone(12), Optional.of("203.0.113.99"));
        clock.advance(Duration.ofMinutes(61));
        issue(UUID.randomUUID(), phone(13), IP);
    }

    @Test
    void dailyCapStopsAllSendingAndResetsAfterADay() {
        parameters.set(OtpLimits.KEY_SMS_DAILY_CAP, 3);
        for (int i = 1; i <= 3; i++) {
            issue(UUID.randomUUID(), phone(i), Optional.of("203.0.113." + i));
        }

        ProblemException capped = problem(() -> otp.admitConfirmation(UUID.randomUUID(), phone(4), Optional.of("203.0.113.9")));
        assertEquals(ProblemType.OTP_REQUEST_LIMITED, capped.type());
        assertEquals(3, sms.count());

        clock.advance(Duration.ofHours(24).plusSeconds(1));
        issue(UUID.randomUUID(), phone(5), Optional.of("203.0.113.10"));
        assertEquals(4, sms.count());
    }

    @Test
    void limitsComeFromPlatformParametersAndFallBackToSeededDefaults() {
        assertEquals(new OtpLimits(3, 10, 10, 1000, 3600), otp.limits());

        parameters.set(OtpLimits.KEY_PER_TEN_MINUTES, 1).set(OtpLimits.KEY_PER_DAY, 2).set(OtpLimits.KEY_PER_IP_HOUR, 4)
                .set(OtpLimits.KEY_SMS_DAILY_CAP, 50).set(OtpLimits.KEY_LOCK_SECONDS, 120);
        assertEquals(new OtpLimits(1, 2, 4, 50, 120), otp.limits());

        issue(USER, PHONE, IP);
        assertEquals(ProblemType.OTP_REQUEST_LIMITED, problem(() -> otp.admitConfirmation(USER, PHONE, IP)).type());
    }

    @Test
    void resendTimeIsEmptyWhileRequestsRemainAndSetWhenTheWindowIsUsedUp() {
        assertEquals(Optional.empty(), issue(USER, PHONE, IP).resendAt());
        assertEquals(Optional.empty(), issue(USER, PHONE, IP).resendAt());
        clock.advance(Duration.ofSeconds(60));

        OtpService.Issued third = issue(USER, PHONE, IP);

        assertEquals(Optional.of(T0.plusSeconds(600)), third.resendAt(), "третий запрос исчерпал окно: новый код после освобождения первого");
    }

    @Test
    void redisDownMeansNoSmsAndAnswer503() {
        store.down(true);

        ProblemException admit = problem(() -> otp.admitConfirmation(USER, PHONE, IP));
        ProblemException login = problem(() -> otp.issueLogin(PHONE, IP, Optional.of(USER)));
        ProblemException verify = problem(() -> otp.verifyLogin(PHONE, "123456"));

        for (ProblemException e : new ProblemException[] {admit, login, verify}) {
            assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
            assertTrue(e.headers().containsKey("Retry-After"));
        }
        assertEquals(0, sms.count());
    }

    // ---- вход по SMS: нейтральность (ST-06) и замок номера ----

    @Test
    void loginForBoundAndUnknownNumbersIsIndistinguishableAtEveryStep() {
        codes.then("123456");
        PhoneNumber known = phone(1);
        PhoneNumber unknown = phone(2);
        otp.issueLogin(known, IP, Optional.of(USER));
        otp.issueLogin(unknown, IP, Optional.empty());
        assertEquals(1, sms.count(), "SMS ушло только на привязанный номер");

        // Неверный код
        ProblemException wrongKnown = problem(() -> otp.verifyLogin(known, "000000"));
        ProblemException wrongUnknown = problem(() -> otp.verifyLogin(unknown, "000000"));
        assertEquals(wrongKnown.type(), wrongUnknown.type());
        assertEquals(wrongKnown.extensions(), wrongUnknown.extensions());
        assertEquals(wrongKnown.getMessage(), wrongUnknown.getMessage());
        assertEquals(ProblemType.OTP_INVALID, wrongKnown.type());

        // Исчерпание попыток на обоих
        for (int i = 0; i < 3; i++) {
            problem(() -> otp.verifyLogin(known, "000000"));
            problem(() -> otp.verifyLogin(unknown, "000000"));
        }
        ProblemException lockedKnown = problem(() -> otp.verifyLogin(known, "000000"));
        ProblemException lockedUnknown = problem(() -> otp.verifyLogin(unknown, "000000"));
        assertEquals(ProblemType.OTP_LOCKED, lockedKnown.type());
        assertEquals(lockedKnown.type(), lockedUnknown.type());
        assertEquals(lockedKnown.headers(), lockedUnknown.headers());
    }

    @Test
    void decoyNeverVerifiesEvenWhenTheAttackerGuessesItsHash() {
        otp.issueLogin(phone(2), IP, Optional.empty());

        for (int code = 0; code < 4; code++) {
            ProblemException e = problem(() -> otp.verifyLogin(phone(2), "000000"));
            assertEquals(ProblemType.OTP_INVALID, e.type());
        }
        assertTrue(store.codes().values().stream().allMatch(c -> c.userId().isEmpty()));
    }

    @Test
    void expiredLoginCodeAnswersTheSameForBoundAndUnknownNumbers() {
        codes.then("123456");
        otp.issueLogin(phone(1), IP, Optional.of(USER));
        otp.issueLogin(phone(2), IP, Optional.empty());
        clock.advance(Duration.ofSeconds(5 * 60 + 1));

        ProblemException known = problem(() -> otp.verifyLogin(phone(1), "123456"));
        ProblemException unknown = problem(() -> otp.verifyLogin(phone(2), "123456"));

        assertEquals(ProblemType.OTP_EXPIRED, known.type());
        assertEquals(known.type(), unknown.type());
    }

    @Test
    void correctLoginCodeReturnsTheAccountAndIsSingleUse() {
        codes.then("123456");
        otp.issueLogin(PHONE, IP, Optional.of(USER));

        assertEquals(USER, otp.verifyLogin(PHONE, "123456"));
        ProblemException again = problem(() -> otp.verifyLogin(PHONE, "123456"));

        assertEquals(ProblemType.OTP_INVALID, again.type(), "после погашения запись пуста, ответ как у неверного кода");
    }

    @Test
    void threeBurnedCodesInAnHourCloseTheNumberForAnHour() {
        for (int round = 0; round < 3; round++) {
            codes.then(String.format("%06d", round + 1));
            otp.issueLogin(PHONE, Optional.of("203.0.113." + (round + 1)), Optional.of(USER));
            for (int i = 0; i < 4; i++) {
                assertEquals(ProblemType.OTP_INVALID, problem(() -> otp.verifyLogin(PHONE, "999999")).type());
            }
            ProblemException exhausted = problem(() -> otp.verifyLogin(PHONE, "999999"));
            assertEquals(ProblemType.OTP_LOCKED, exhausted.type());
            if (round < 2) {
                clock.advance(Duration.ofMinutes(11));
            }
        }

        ProblemException request = problem(() -> otp.issueLogin(PHONE, Optional.of("203.0.113.50"), Optional.of(USER)));
        assertEquals(ProblemType.OTP_REQUEST_LIMITED, request.type(), "номер закрыт: новый код не выпускается");
        assertEquals("3600", request.headers().get("Retry-After"));
        ProblemException verify = problem(() -> otp.verifyLogin(PHONE, "123456"));
        assertEquals(ProblemType.OTP_LOCKED, verify.type());

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        codes.then("424242");
        otp.issueLogin(PHONE, Optional.of("203.0.113.51"), Optional.of(USER));
        assertEquals(USER, otp.verifyLogin(PHONE, "424242"), "через час замок снят");
    }

    @Test
    void correctLoginCodeResetsTheBurnedCount() {
        for (int round = 0; round < 2; round++) {
            codes.then(String.format("%06d", round + 1));
            otp.issueLogin(PHONE, Optional.of("203.0.113." + (round + 1)), Optional.of(USER));
            for (int i = 0; i < 5; i++) {
                problem(() -> otp.verifyLogin(PHONE, "999999"));
            }
            clock.advance(Duration.ofMinutes(11));
        }
        codes.then("777777");
        otp.issueLogin(PHONE, Optional.of("203.0.113.30"), Optional.of(USER));
        assertEquals(USER, otp.verifyLogin(PHONE, "777777"));
        clock.advance(Duration.ofMinutes(11));

        codes.then("888888");
        otp.issueLogin(PHONE, Optional.of("203.0.113.31"), Optional.of(USER));
        for (int i = 0; i < 5; i++) {
            problem(() -> otp.verifyLogin(PHONE, "999999"));
        }
        codes.then("555555");
        clock.advance(Duration.ofMinutes(11));
        otp.issueLogin(PHONE, Optional.of("203.0.113.32"), Optional.of(USER));
        assertEquals(USER, otp.verifyLogin(PHONE, "555555"), "три подряд не набрались: верный код между ними обнулил счёт");
    }

    @Test
    void loginSmsFailureStaysSilentSoTheAnswerDoesNotRevealTheBinding() {
        sms.failing(true);

        otp.issueLogin(PHONE, IP, Optional.of(USER));

        assertEquals(0, sms.count());
    }

    @Test
    void loginRequestsCountInLimitsOfPhoneAndIp() {
        for (int i = 0; i < 3; i++) {
            otp.issueLogin(PHONE, IP, Optional.empty());
        }

        ProblemException limited = problem(() -> otp.issueLogin(PHONE, IP, Optional.empty()));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
    }

    @Test
    void secondsRoundUpAndNeverDropBelowOne() {
        assertEquals(1, OtpService.seconds(Duration.ofMillis(1)));
        assertEquals(1, OtpService.seconds(Duration.ZERO));
        assertEquals(2, OtpService.seconds(Duration.ofMillis(1001)));
        assertEquals(60, OtpService.seconds(Duration.ofSeconds(60)));
    }
}
