package dgm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;

/** Перевод лимитов «запросов в минуту» в корзину токенов Redis и разбор ответа Spring Cloud Gateway. */
class RedisRequestLimiterTest {

    private static GatewayProperties.Limit limit(int perMinute, int burst) {
        GatewayProperties.Limit limit = new GatewayProperties.Limit();
        limit.setPerMinute(perMinute);
        limit.setBurst(burst);
        return limit;
    }

    private static void assertBucket(int perMinute, int burst, long rate, int cost, long capacity) {
        RedisRateLimiter.Config config = RedisRequestLimiter.tokens(limit(perMinute, burst));
        assertEquals(rate, config.getReplenishRate(), perMinute + "/мин: токенов в секунду");
        assertEquals(cost, config.getRequestedTokens(), perMinute + "/мин: токенов на запрос");
        assertEquals(capacity, config.getBurstCapacity(), perMinute + "/мин: ёмкость корзины");
    }

    @Test
    void limitsAreConvertedToWholeTokensPerSecond() {
        assertBucket(10, 10, 1, 6, 60);       // 10 в минуту: запрос стоит 6 токенов при 1 токене в секунду
        assertBucket(60, 120, 1, 1, 120);
        assertBucket(120, 120, 2, 1, 120);
        assertBucket(30, 30, 1, 2, 60);
        assertBucket(300, 300, 5, 1, 300);
        assertBucket(7, 7, 7, 60, 420);       // взаимно простое с 60: токены считаются секундами минуты
    }

    @Test
    void throughputInOneMinuteEqualsTheLimit() {
        for (int perMinute : new int[] {1, 2, 7, 10, 30, 45, 60, 120, 300, 600}) {
            RedisRateLimiter.Config config = RedisRequestLimiter.tokens(limit(perMinute, perMinute));
            double requests = 60.0 * config.getReplenishRate() / config.getRequestedTokens();
            assertEquals(perMinute, requests, 1e-9, "за минуту корзина пополняется ровно на лимит запросов");
        }
    }

    @Test
    void nonPositiveLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RedisRequestLimiter.tokens(limit(0, 10)));
        assertThrows(IllegalArgumentException.class, () -> RedisRequestLimiter.tokens(limit(10, 0)));
    }

    private static RateLimiter.Response response(boolean allowed, String remaining) {
        return new RateLimiter.Response(allowed, remaining == null ? Map.of() : Map.of(RedisRateLimiter.REMAINING_HEADER, remaining));
    }

    @Test
    void allowedAndDeniedDecisions() {
        RedisRateLimiter.Config config = RedisRequestLimiter.tokens(limit(10, 10));   // 6 токенов на запрос, 1 в секунду
        RequestLimiter.Decision allowed = RedisRequestLimiter.decision(response(true, "40"), config);
        assertTrue(allowed.allowed());
        assertFalse(allowed.failOpen());

        RequestLimiter.Decision denied = RedisRequestLimiter.decision(response(false, "3"), config);
        assertFalse(denied.allowed());
        assertFalse(denied.failOpen());
        assertEquals(3, denied.retryAfterSeconds(), "не хватает 3 токенов, токен в секунду");

        RequestLimiter.Decision empty = RedisRequestLimiter.decision(response(false, "0"), config);
        assertEquals(6, empty.retryAfterSeconds());
        assertEquals(6, RedisRequestLimiter.decision(response(false, null), config).retryAfterSeconds());
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        RedisRateLimiter.Config config = RedisRequestLimiter.tokens(limit(300, 300));   // 1 токен на запрос, 5 в секунду
        assertEquals(1, RedisRequestLimiter.decision(response(false, "0"), config).retryAfterSeconds());
    }

    @Test
    void redisFailureIsFailOpenNotARemainder() {
        // RedisRateLimiter при сбое Redis сам пропускает запрос и пишет в остаток -1
        RedisRateLimiter.Config config = RedisRequestLimiter.tokens(limit(60, 120));
        RequestLimiter.Decision decision = RedisRequestLimiter.decision(response(true, "-1"), config);
        assertTrue(decision.allowed());
        assertTrue(decision.failOpen());
    }
}
