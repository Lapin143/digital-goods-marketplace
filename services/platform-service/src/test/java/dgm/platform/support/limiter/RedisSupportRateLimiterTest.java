package dgm.platform.support.limiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Разбор ответов скрипта окна и параметры ключа: сам скрипт на настоящем Redis проверяют интеграционные тесты платформы. */
class RedisSupportRateLimiterTest {

    @Test
    void deniedAnswerCarriesTheWaitInMilliseconds() {
        assertEquals(Optional.of(Duration.ofMillis(3_300_000)), RedisSupportRateLimiter.parse("D:3300000"));
    }

    @Test
    void allowedAnswerMeansNoWait() {
        assertTrue(RedisSupportRateLimiter.parse("A:3:0").isEmpty());
        assertTrue(RedisSupportRateLimiter.parse("A:0:1200000").isEmpty());
    }

    @Test
    void anythingElseIsAnError() {
        assertThrows(IllegalStateException.class, () -> RedisSupportRateLimiter.parse(""));
        assertThrows(IllegalStateException.class, () -> RedisSupportRateLimiter.parse("X:1"));
    }

    @Test
    void theKeyStaysUnderThePrefixOfTheRedisAclUser() {
        assertTrue(RedisSupportRateLimiter.KEY_PREFIX.startsWith("platform:"), "пользователь Redis platform видит только ключи platform:*");
        assertEquals(Duration.ofHours(1), RedisSupportRateLimiter.WINDOW);
    }
}
