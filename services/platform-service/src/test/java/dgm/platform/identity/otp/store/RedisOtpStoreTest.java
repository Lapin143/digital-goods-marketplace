package dgm.platform.identity.otp.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.platform.identity.otp.service.OtpStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Разбор ответов скриптов Lua. Сами скрипты выполняет Redis, их проверяет {@code RedisOtpStoreIT}. */
class RedisOtpStoreTest {

    @Test
    void admissionAnswers() {
        OtpStore.Admission denied = RedisOtpStore.parseAdmission("D:510000");
        assertFalse(denied.allowed());
        assertEquals(Duration.ofMillis(510_000), denied.retryAfter());

        OtpStore.Admission allowed = RedisOtpStore.parseAdmission("A:2:0");
        assertTrue(allowed.allowed());
        assertEquals(2, allowed.remaining());
        assertEquals(Duration.ZERO, allowed.resendAfter());

        OtpStore.Admission last = RedisOtpStore.parseAdmission("A:0:540000");
        assertEquals(0, last.remaining());
        assertEquals(Duration.ofMillis(540_000), last.resendAfter());

        assertThrows(IllegalStateException.class, () -> RedisOtpStore.parseAdmission("X"));
        assertThrows(IllegalStateException.class, () -> RedisOtpStore.parseAdmission("A:1"));
    }

    @Test
    void redemptionAnswers() {
        assertEquals(OtpStore.Result.ABSENT, RedisOtpStore.parseRedemption("N").result());
        assertEquals(OtpStore.Result.OTHER, RedisOtpStore.parseRedemption("M").result());
        assertEquals(OtpStore.Result.EXPIRED, RedisOtpStore.parseRedemption("E").result());

        OtpStore.Redemption invalid = RedisOtpStore.parseRedemption("I:3");
        assertEquals(OtpStore.Result.INVALID, invalid.result());
        assertEquals(3, invalid.attemptsLeft());

        OtpStore.Redemption exhausted = RedisOtpStore.parseRedemption("L:0");
        assertEquals(OtpStore.Result.EXHAUSTED, exhausted.result());
        assertEquals(Duration.ZERO, exhausted.lockedFor());
        assertEquals(Duration.ofHours(1), RedisOtpStore.parseRedemption("L:3600000").lockedFor());

        OtpStore.Redemption locked = RedisOtpStore.parseRedemption("K:120000");
        assertEquals(OtpStore.Result.LOCKED, locked.result());
        assertEquals(Duration.ofMinutes(2), locked.lockedFor());

        OtpStore.Redemption valid = RedisOtpStore.parseRedemption("V:0199e0a0-0000-4000-8000-0000000000a1:+79001234567");
        assertEquals(OtpStore.Result.VALID, valid.result());
        assertEquals("0199e0a0-0000-4000-8000-0000000000a1", valid.userId());
        assertEquals("+79001234567", valid.phone());

        OtpStore.Redemption loginValid = RedisOtpStore.parseRedemption("V:0199e0a0-0000-4000-8000-0000000000a1:");
        assertEquals("", loginValid.phone());

        assertThrows(IllegalStateException.class, () -> RedisOtpStore.parseRedemption("Z"));
        assertThrows(IllegalStateException.class, () -> RedisOtpStore.parseRedemption("V:only-user"));
    }

    @Test
    void redemptionToStringNeverShowsUserOrPhone() {
        String text = RedisOtpStore.parseRedemption("V:0199e0a0-0000-4000-8000-0000000000a1:+79001234567").toString();

        assertEquals("Redemption[VALID]", text);
    }
}
