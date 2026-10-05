package dgm.gateway;

import java.util.HashMap;
import java.util.Map;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import reactor.core.publisher.Mono;

/**
 * Распределённый лимит на {@link RedisRateLimiter} Spring Cloud Gateway: «корзина токенов» в Redis, один скрипт Lua на запрос
 * (ADR-021). Лимиты заданы в запросах в минуту, а корзина считает токены в секунду целым числом, поэтому запрос стоит
 * {@code 60 / НОД(лимит, 60)} токенов, а корзина пополняется на {@code лимит / НОД(лимит, 60)} токенов в секунду: 10 запросов в
 * минуту это 6 токенов за запрос при 1 токене в секунду. Ключи Redis: {@code request_rate_limiter.{группа|ключ}.*}, их разрешает ACL
 * пользователя {@code gateway}.
 */
public final class RedisRequestLimiter implements RequestLimiter {

    private final RedisRateLimiter delegate;
    private final Map<String, RedisRateLimiter.Config> configs = new HashMap<>();

    public RedisRequestLimiter(RedisRateLimiter delegate, Map<String, GatewayProperties.Limit> limits) {
        this.delegate = delegate;
        limits.forEach((group, limit) -> {
            RedisRateLimiter.Config config = tokens(limit);
            configs.put(group, config);
            delegate.getConfig().put(group, config);
        });
    }

    /** Настройки корзины для лимита группы. */
    static RedisRateLimiter.Config tokens(GatewayProperties.Limit limit) {
        int perMinute = limit.getPerMinute();
        if (perMinute <= 0 || limit.getBurst() <= 0) {
            throw new IllegalArgumentException("Лимит должен быть положительным: " + perMinute + " в минуту, запас " + limit.getBurst());
        }
        int divisor = gcd(perMinute, 60);
        int cost = 60 / divisor;
        return new RedisRateLimiter.Config()
                .setReplenishRate(perMinute / divisor)
                .setRequestedTokens(cost)
                .setBurstCapacity((long) limit.getBurst() * cost);
    }

    @Override
    public Mono<Decision> check(String group, String key) {
        RedisRateLimiter.Config config = configs.get(group);
        if (config == null) {
            return Mono.error(new IllegalStateException("Для группы лимита " + group + " нет настроек"));
        }
        return delegate.isAllowed(group, group + "|" + key).map(response -> decision(response, config));
    }

    /**
     * Ответ Spring в решение. При сбое Redis {@link RedisRateLimiter} сам пропускает запрос и пишет в заголовок остатка {@code -1}:
     * это сбой, а не остаток, поэтому он тоже считается отказом открытым.
     */
    static Decision decision(RateLimiter.Response response, RedisRateLimiter.Config config) {
        long left = parse(response.getHeaders().get(RedisRateLimiter.REMAINING_HEADER));
        if (left < 0) {
            return Decision.unchecked();
        }
        if (response.isAllowed()) {
            return Decision.allow();
        }
        long missing = Math.max(1, config.getRequestedTokens() - left);
        return Decision.deny((int) Math.ceil((double) missing / config.getReplenishRate()));
    }

    private static long parse(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
