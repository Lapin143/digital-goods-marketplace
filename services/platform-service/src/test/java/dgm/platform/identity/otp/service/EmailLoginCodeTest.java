package dgm.platform.identity.otp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.platform.identity.otp.crypto.OtpCrypto;
import dgm.platform.identity.otp.domain.OtpPurpose;
import dgm.platform.identity.otp.testing.FakeParameters;
import dgm.platform.identity.otp.testing.InMemoryOtpStore;
import dgm.platform.identity.otp.testing.RecordingMail;
import dgm.platform.identity.otp.testing.RecordingSms;
import dgm.platform.identity.otp.testing.ScriptedCodes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Код из письма при входе через VK ID без адреса и счётчик писем по адресу (Р-194, F4-1, F4-3) без Redis и без ожидания. Код: шесть цифр,
 * срок 5 минут, пять попыток, хранится только хешем, привязан к адресу, письмо уходит на любой названный адрес. Счётчик: три письма за десять
 * минут и десять за сутки на адрес, общий для всех шагов, отказ ничего не расходует, при недоступном Redis письмо не отправляется.
 */
class EmailLoginCodeTest {

    private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");
    private static final String EMAIL = "vk.buyer@mail.example";

    private ManualClock clock;
    private InMemoryOtpStore store;
    private RecordingMail mail;
    private ScriptedCodes codes;
    private OtpCrypto crypto;
    private SimpleMeterRegistry meters;
    private OtpService otp;
    private VkEmailCodeService vk;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        store = new InMemoryOtpStore(clock);
        mail = new RecordingMail();
        codes = new ScriptedCodes();
        meters = new SimpleMeterRegistry();
        crypto = OtpCrypto.ofBytes("test-pepper-0123456789abcdef-0123".getBytes(StandardCharsets.UTF_8));
        otp = new OtpService(store, crypto, codes, new RecordingSms(), new FakeParameters(), OtpProperties.defaults(), clock, meters);
        vk = new VkEmailCodeService(otp, mail);
    }

    private static ProblemException problem(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    private double quota(String step, String result) {
        return meters.counter("email_quota_total", "purpose", step, "result", result).count();
    }

    // ---- код из письма (TC-140, TC-141) ----

    @Tag("TC-140")
    @Test
    void theCodeGoesToTheNormalizedAddressAndIsStoredOnlyAsAKeyedHash() {
        codes.then("012345");

        vk.requestCode("  Vk.Buyer@Mail.Example ");

        assertEquals(1, mail.loginCodes().size());
        assertEquals(EMAIL, mail.loginCodes().get(0).to(), "адрес приведён к нижнему регистру без пробелов по краям");
        assertEquals("012345", mail.loginCodes().get(0).code());
        assertEquals(0, mail.sent().size(), "письмо смены e-mail тут не отправляется");
        OtpStore.Code stored = store.code("code:vk_email:").orElseThrow();
        assertEquals(crypto.codeHash(OtpPurpose.VK_EMAIL, crypto.target("email", EMAIL), "012345"), stored.hash());
        assertTrue(stored.hash().matches("[0-9a-f]{64}"));
        assertEquals(T0.plusSeconds(300), stored.expiresAt(), "срок 5 минут");
        assertEquals(5, stored.attempts());
        assertFalse(stored.toString().contains("012345"));
        for (String key : store.keys()) {
            assertFalse(key.contains("buyer") || key.contains("mail.example"), "адрес в ключе Redis: " + key);
        }
        assertEquals(1.0, meters.counter("otp_events_total", "purpose", "vk_email", "event", "sent").count());
    }

    @Tag("TC-140")
    @Test
    void aCorrectCodeConfirmsTheMailboxAndIsSingleUse() {
        codes.then("123456");
        vk.requestCode(EMAIL);

        vk.verifyCode(EMAIL, "123456");
        ProblemException again = problem(() -> vk.verifyCode(EMAIL, "123456"));

        assertEquals(ProblemType.OTP_EXPIRED, again.type(), "погашенный код второй раз не принимается");
    }

    @Tag("TC-141")
    @Test
    void theCodeLivesFiveMinutesAndExpiresOneSecondLater() {
        codes.then("111111", "222222");
        vk.requestCode(EMAIL);
        clock.advance(Duration.ofMinutes(5));
        vk.verifyCode(EMAIL, "111111");

        vk.requestCode(EMAIL);
        clock.advance(Duration.ofSeconds(5 * 60 + 1));
        ProblemException expired = problem(() -> vk.verifyCode(EMAIL, "222222"));

        assertEquals(ProblemType.OTP_EXPIRED, expired.type());
    }

    @Tag("TC-141")
    @Test
    void theFifthWrongAttemptLocksAndTheRightCodeAfterItDoesNotWork() {
        codes.then("111111");
        vk.requestCode(EMAIL);

        for (int left = 4; left >= 1; left--) {
            ProblemException wrong = problem(() -> vk.verifyCode(EMAIL, "000000"));
            assertEquals(ProblemType.OTP_INVALID, wrong.type());
            assertEquals(left, wrong.extensions().get("attemptsLeft"));
        }
        ProblemException locked = problem(() -> vk.verifyCode(EMAIL, "000000"));
        assertEquals(ProblemType.OTP_LOCKED, locked.type());
        assertTrue(Integer.parseInt(locked.headers().get("Retry-After")) > 0);

        ProblemException sixth = problem(() -> vk.verifyCode(EMAIL, "111111"));
        assertEquals(ProblemType.OTP_EXPIRED, sixth.type(), "код погашен исчерпанием попыток");
    }

    @Tag("TC-141")
    @Test
    void aNewRequestRevokesThePreviousCodeAndACodeOfAnotherAddressDoesNotMatch() {
        codes.then("111111", "222222", "333333");
        vk.requestCode(EMAIL);
        vk.requestCode(EMAIL);

        ProblemException old = problem(() -> vk.verifyCode(EMAIL, "111111"));
        assertEquals(ProblemType.OTP_INVALID, old.type(), "прежний код погашен новым запросом");

        vk.requestCode("other.guest@mail.example");
        ProblemException crossed = problem(() -> vk.verifyCode(EMAIL, "333333"));
        assertEquals(ProblemType.OTP_INVALID, crossed.type(), "код другого адреса не подходит");
        vk.verifyCode(EMAIL, "222222");
        vk.verifyCode("other.guest@mail.example", "333333");
    }

    @Tag("TC-141")
    @Test
    void theCodeOfAnotherPurposeForTheSameAddressDoesNotMatch() {
        codes.then("123456");
        String target = crypto.target("email", EMAIL);

        assertFalse(crypto.codeHash(OtpPurpose.VK_EMAIL, target, "123456").equals(crypto.codeHash(OtpPurpose.EMAIL_CHANGE_NEW_EMAIL, target, "123456")),
                "хеш зависит от назначения: код смены e-mail для входа через VK ID не годится");
    }

    @Tag("TC-141")
    @Test
    void aMailFailureRemovesTheCodeAndAnswers503WithoutTheAddressInTheText() {
        codes.then("123456");
        mail.failing(true);

        ProblemException failure = problem(() -> vk.requestCode(EMAIL));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, failure.type());
        assertEquals("30", failure.headers().get("Retry-After"));
        assertFalse(failure.getMessage().contains("mail.example"), "адрес в тексте ошибки");
        assertTrue(store.codes().isEmpty(), "код, который некому вручить, не остаётся в Redis");
        assertEquals(1.0, meters.counter("otp_events_total", "purpose", "vk_email", "event", "send_failed").count());
    }

    @Tag("TC-142")
    @Test
    void theMailIsSentToAnyNamedAddressSoTheAnswerDoesNotShowWhetherTheAddressIsTaken() {
        codes.then("111111", "222222");

        vk.requestCode("taken@mail.example");
        vk.requestCode("free@mail.example");

        assertEquals(2, mail.loginCodes().size(), "платформа учётные записи не смотрит: одинаково для занятого и свободного");
        assertEquals(2, store.codes().size());
    }

    @Tag("TC-141")
    @Test
    void redisDownMeansNoMailNoCodeAndAnswer503() {
        store.down(true);

        ProblemException request = problem(() -> vk.requestCode(EMAIL));
        ProblemException verify = problem(() -> vk.verifyCode(EMAIL, "123456"));

        for (ProblemException e : new ProblemException[] {request, verify}) {
            assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
            assertTrue(e.headers().containsKey("Retry-After"));
        }
        assertEquals(0, mail.loginCodes().size());
    }

    // ---- счётчик писем по адресу (TC-143) ----

    @Tag("TC-143")
    @Test
    void theFourthMailInTenMinutesIsRefusedWithRetryAfterAndFreesUpLater() {
        for (int i = 0; i < 3; i++) {
            otp.consumeEmailQuota("register", EMAIL);
            clock.advance(Duration.ofSeconds(30));
        }

        ProblemException limited = problem(() -> otp.consumeEmailQuota("register", EMAIL));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        long retry = Long.parseLong(limited.headers().get("Retry-After"));
        assertEquals(510, retry, "первое письмо освободит окно через 10 минут минус прошедшие 90 секунд");
        assertEquals(limited.extensions().get("retryAfterAt"), dgm.kit.time.Timestamps.format(clock.instant().plusSeconds(retry)));
        assertEquals(3.0, quota("register", "allowed"));
        assertEquals(1.0, quota("register", "limited"));

        clock.advance(Duration.ofSeconds(retry));
        otp.consumeEmailQuota("register", EMAIL);
    }

    @Tag("TC-143")
    @Test
    void theCounterIsSharedByRegistrationResetAndTheVkStep() {
        otp.consumeEmailQuota("register", EMAIL);
        otp.consumeEmailQuota("reset", EMAIL);
        otp.consumeEmailQuota("vk_email", EMAIL);

        for (String step : new String[] {"register", "reset", "vk_email"}) {
            assertEquals(ProblemType.OTP_REQUEST_LIMITED, problem(() -> otp.consumeEmailQuota(step, EMAIL)).type(), step);
        }
    }

    @Tag("TC-143")
    @Test
    void theAddressIsComparedWithoutCaseAndWhitespaceAndOtherAddressesHaveTheirOwnCounters() {
        otp.consumeEmailQuota("register", EMAIL);
        otp.consumeEmailQuota("register", EMAIL.toUpperCase());
        otp.consumeEmailQuota("register", "  " + EMAIL + "  ");

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, problem(() -> otp.consumeEmailQuota("register", "VK.Buyer@MAIL.example")).type());
        otp.consumeEmailQuota("register", "another@mail.example");
    }

    @Tag("TC-143")
    @Test
    void tenMailsPerDayThenRefusedAndResetsAfterADay() {
        for (int i = 0; i < 10; i++) {
            otp.consumeEmailQuota("register", EMAIL);
            clock.advance(Duration.ofMinutes(11));
        }

        ProblemException limited = problem(() -> otp.consumeEmailQuota("register", EMAIL));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        assertTrue(Long.parseLong(limited.headers().get("Retry-After")) > 600, "дневное окно освободится не через десять минут");
        clock.advance(Duration.ofHours(24));
        otp.consumeEmailQuota("register", EMAIL);
    }

    @Tag("TC-143")
    @Test
    void aRefusedRequestSpendsNothingAndDoesNotExtendItsOwnBlock() {
        for (int i = 0; i < 3; i++) {
            otp.consumeEmailQuota("vk_email", EMAIL);
        }
        long first = Long.parseLong(problem(() -> otp.consumeEmailQuota("vk_email", EMAIL)).headers().get("Retry-After"));
        clock.advance(Duration.ofSeconds(100));
        for (int i = 0; i < 10; i++) {
            problem(() -> otp.consumeEmailQuota("vk_email", EMAIL));
        }

        long later = Long.parseLong(problem(() -> otp.consumeEmailQuota("vk_email", EMAIL)).headers().get("Retry-After"));

        assertEquals(first - 100, later, "настойчивые повторы блокировку не продлевают");
    }

    @Tag("TC-143")
    @Test
    void theCounterIsTheSameWindowAsTheMailOfAnEmailChangeToTheSameAddress() {
        for (int i = 0; i < 3; i++) {
            otp.consumeEmailQuota("register", EMAIL);
        }

        ProblemException change = problem(() -> otp.admitEmailChangeNewEmail(UUID.randomUUID(), EMAIL, Optional.empty()));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, change.type(), "письмо чужому ящику нельзя слать, чередуя регистрацию и смену e-mail");
    }

    @Tag("TC-143")
    @Test
    void theLimitsComeFromPlatformParameters() {
        FakeParameters parameters = new FakeParameters();
        parameters.set(dgm.platform.identity.otp.domain.OtpLimits.KEY_PER_TEN_MINUTES, 1);
        OtpService strict = new OtpService(store, crypto, codes, new RecordingSms(), parameters, OtpProperties.defaults(), clock, meters);

        strict.consumeEmailQuota("reset", EMAIL);

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, problem(() -> strict.consumeEmailQuota("reset", EMAIL)).type());
    }

    @Tag("TC-143")
    @Test
    void redisDownMeansNoMailAndAnswer503() {
        store.down(true);

        ProblemException e = problem(() -> otp.consumeEmailQuota("register", EMAIL));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type(), "без счётчика письмо не отправляется (режим отказа, ADR-010)");
        assertTrue(e.headers().containsKey("Retry-After"));
    }

    @Tag("TC-143")
    @Test
    void noAddressReachesRedisKeysMetricsOrErrorTexts() {
        otp.consumeEmailQuota("register", EMAIL);
        for (int i = 0; i < 2; i++) {
            otp.consumeEmailQuota("register", EMAIL);
        }
        ProblemException limited = problem(() -> otp.consumeEmailQuota("register", EMAIL));

        for (String key : store.keys()) {
            assertFalse(key.contains("buyer") || key.contains("mail.example"), "адрес в ключе Redis: " + key);
        }
        assertFalse(limited.getMessage().contains("mail.example"));
        assertFalse(String.valueOf(limited.extensions()).contains("mail.example"));
        assertEquals(3.0, quota("register", "allowed"), "в метрике только шаг и исход, адреса среди меток нет");
    }
}
