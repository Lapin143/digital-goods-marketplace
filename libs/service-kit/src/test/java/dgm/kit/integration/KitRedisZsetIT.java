package dgm.kit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.redis.KitRedis;
import dgm.kit.redis.RedisSettings;
import dgm.kit.secret.SecretFiles;
import dgm.kit.testing.Concurrent;
import dgm.kit.testing.Stand;
import dgm.kit.tls.TlsMaterial;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Отсортированные множества в {@link KitRedis} на настоящем Redis стенда (Р-141, ADR-012): добавление и смена оценки, выборка наступивших
 * по возрастанию оценки с пределом, удаление, из двух одновременных удалений выигрывает одно, оценка в миллисекундах от эпохи, ACL по
 * префиксу ключа. Нужен стенд с отладочным портом: {@code make up SET=dev-kit DEBUG=1}. Пользователь ACL {@code catalog}, ключи
 * {@code catalog:*}: правила ACL одинаковы у всех сервисов, а таймеры резервов проверяет интеграционный тест inventory-service.
 */
@Tag("integration")
class KitRedisZsetIT {

    private static final String PREFIX = "catalog:it:zset:" + Env.RUN + ":";

    private static KitRedis redis;
    private static final List<String> USED = new ArrayList<>();

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeAll
    static void connect() {
        TlsMaterial tls = TlsMaterial.forService(Stand.secrets(), "catalog-service");
        RedisSettings settings = new RedisSettings(env("DGM_REDIS_HOST", "localhost"), Integer.parseInt(env("DGM_REDIS_PORT", "16379")), "catalog",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        redis = KitRedis.create(settings, () -> SecretFiles.read("redis_catalog").reveal().toCharArray(), tls);
    }

    @AfterAll
    static void close() {
        for (String key : USED) {
            try {
                redis.delete(key);
            } catch (ProblemException e) {
                // очистка не должна скрывать итог тестов
            }
        }
        redis.close();
    }

    private static String key(String name) {
        String key = PREFIX + name;
        USED.add(key);
        return key;
    }

    @Test
    void addReportsNewMembersAndMovingAnExistingOne() {
        String key = key("add");
        assertEquals(0, redis.zcard(key));
        assertTrue(redis.zadd(key, 100, "a"));
        assertTrue(redis.zadd(key, 200, "b"));
        assertFalse(redis.zadd(key, 300, "a"), "участник уже есть: меняется только оценка");
        assertEquals(2, redis.zcard(key));
        assertEquals(List.of("b"), redis.zrangeByScore(key, 0, 250, 10), "после смены оценки «a» не входит в диапазон до 250");
        assertEquals(List.of("b", "a"), redis.zrangeByScore(key, 0, 300, 10));
    }

    @Test
    void rangeReturnsDueMembersByScoreWithLimitAndBounds() {
        String key = key("range");
        redis.zadd(key, 400, "m4");
        redis.zadd(key, 100, "m1");
        redis.zadd(key, 300, "m3");
        redis.zadd(key, 200, "m2");
        assertEquals(List.of("m1", "m2", "m3"), redis.zrangeByScore(key, 0, 300, 10), "граница включена, порядок по оценке");
        assertEquals(List.of("m1", "m2"), redis.zrangeByScore(key, 0, 400, 2), "предел выборки");
        assertEquals(List.of("m2", "m3"), redis.zrangeByScore(key, 150, 350, 10));
        assertEquals(List.of(), redis.zrangeByScore(key, 500, 900, 10));
        assertEquals(4, redis.zcard(key), "выборка участников не удаляет");
    }

    @Test
    void scoresInEpochMillisecondsKeepFullPrecision() {
        String key = key("epoch");
        long now = 1_760_000_000_123L;
        redis.zadd(key, now, "exact");
        redis.zadd(key, now + 1, "next");
        assertEquals(List.of("exact"), redis.zrangeByScore(key, now, now, 10));
        assertEquals(List.of("exact", "next"), redis.zrangeByScore(key, now, now + 1, 10));
        assertEquals(List.of(), redis.zrangeByScore(key, 0, now - 1, 10));
    }

    @Test
    void removeReportsWhetherTheMemberWasThere() {
        String key = key("rem");
        redis.zadd(key, 1, "x");
        assertTrue(redis.zrem(key, "x"));
        assertFalse(redis.zrem(key, "x"));
        assertFalse(redis.zrem(key, "never-added"));
        assertEquals(0, redis.zcard(key));
    }

    @Tag("concurrency")
    @Test
    void ofSeveralExecutorsRemovingTheSameMemberExactlyOneWins() {
        String key = key("race");
        for (int round = 0; round < 20; round++) {
            String member = "reservation-" + round;
            redis.zadd(key, round, member);
            List<Boolean> results = Concurrent.race(4, i -> redis.zrem(key, member));
            assertEquals(1, results.stream().filter(Boolean::booleanValue).count(), "победитель один для " + member);
        }
        assertEquals(0, redis.zcard(key));
    }

    @Test
    void wrongLimitIsRefused() {
        String key = key("limit");
        assertThrows(IllegalArgumentException.class, () -> redis.zrangeByScore(key, 0, 10, 0));
    }

    @Test
    void aclKeepsSetCommandsInsideTheOwnKeys() {
        String foreign = "platform:it:zset:" + Env.RUN + ":foreign";
        ProblemException e = assertThrows(ProblemException.class, () -> redis.zadd(foreign, 1, "m"));
        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals("5", e.headers().get("Retry-After"));
        assertThrows(ProblemException.class, () -> redis.zcard(foreign));
        assertThrows(ProblemException.class, () -> redis.zrangeByScore(foreign, 0, 10, 5));
        assertThrows(ProblemException.class, () -> redis.zrem(foreign, "m"));
    }
}
