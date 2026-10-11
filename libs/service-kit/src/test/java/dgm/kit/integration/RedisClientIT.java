package dgm.kit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.redis.KitRedis;
import dgm.kit.redis.LuaScript;
import dgm.kit.redis.RedisSettings;
import dgm.kit.secret.SecretFiles;
import dgm.kit.testing.Stand;
import dgm.kit.tls.TlsMaterial;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Клиент Redis на настоящем Redis стенда (ADR-022): TLS с клиентским сертификатом, пользователь ACL {@code catalog} с ключами
 * {@code catalog:*}, операции через скрипты Lua, окно счётчика, загрузка неизвестного скрипта, отказ при недоступности и чужом ключе.
 * Нужен стенд с отладочным портом: {@code make up SET=dev-kit DEBUG=1}. Адрес меняют {@code DGM_REDIS_HOST}, {@code DGM_REDIS_PORT}.
 */
@Tag("integration")
class RedisClientIT {

    private static final String PREFIX = "catalog:it:" + Env.RUN + ":";

    private static TlsMaterial tls;
    private static KitRedis redis;
    private static final List<String> USED = new ArrayList<>();

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static char[] password() {
        return SecretFiles.read("redis_catalog").reveal().toCharArray();
    }

    @BeforeAll
    static void connect() {
        tls = TlsMaterial.forService(Stand.secrets(), "catalog-service");
        RedisSettings settings = new RedisSettings(env("DGM_REDIS_HOST", "localhost"), Integer.parseInt(env("DGM_REDIS_PORT", "16379")), "catalog",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        redis = KitRedis.create(settings, RedisClientIT::password, tls);
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
    void connectionWithCertificateAndAclUserIsHealthy() {
        assertTrue(redis.healthy());
    }

    @Test
    void valueRoundTripAndDelete() {
        String key = key("roundtrip");
        assertEquals(Optional.empty(), redis.get(key));
        redis.set(key, "значение", Duration.ofSeconds(30));
        assertEquals(Optional.of("значение"), redis.get(key));
        assertTrue(redis.delete(key));
        assertEquals(Optional.empty(), redis.get(key));
        assertFalse(redis.delete(key));
    }

    @Test
    void valueExpiresAfterItsTtl() throws Exception {
        String key = key("ttl");
        redis.set(key, "short", Duration.ofMillis(300));
        assertEquals(Optional.of("short"), redis.get(key));
        assertTrue(Env.await(Duration.ofSeconds(5), () -> redis.get(key).isEmpty()), "ключ должен исчезнуть сам");
    }

    @Test
    void ttlIsRequiredAndPositive() {
        String key = key("no-ttl");
        assertThrows(IllegalArgumentException.class, () -> redis.set(key, "v", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> redis.set(key, "v", Duration.ofSeconds(-1)));
    }

    @Test
    void setIfAbsentWritesOnlyOnce() {
        String key = key("nx");
        assertTrue(redis.setIfAbsent(key, "first", Duration.ofSeconds(30)));
        assertFalse(redis.setIfAbsent(key, "second", Duration.ofSeconds(30)));
        assertEquals(Optional.of("first"), redis.get(key));
    }

    @Test
    void counterWindowStartsAtTheFirstIncrementAndIsNotExtended() throws Exception {
        String key = key("counter");
        Duration window = Duration.ofSeconds(2);
        assertEquals(1, redis.increment(key, window));
        Thread.sleep(700);
        assertEquals(2, redis.increment(key, window));
        Thread.sleep(1400);
        // С первого увеличения прошло больше окна: второе увеличение его не продлило, счёт начался заново
        assertEquals(1, redis.increment(key, window));
    }

    @Test
    void scriptUnknownToRedisIsLoadedAndRetried() {
        String key = key("script");
        redis.set(key, "stored", Duration.ofSeconds(30));
        // Исходный текст уникален для запуска, поэтому Redis его ещё не знает: первый вызов получает NOSCRIPT, клиент загружает скрипт
        LuaScript script = new LuaScript("it-" + Env.RUN, "-- " + Env.RUN + "\nreturn redis.call('GET', KEYS[1])", LuaScript.Result.TEXT);
        assertEquals("stored", redis.eval(script, List.of(key), List.of()));
        assertEquals("stored", redis.eval(script, List.of(key), List.of()), "повторный вызов идёт по SHA-1 без загрузки");
    }

    @Test
    void aclKeepsTheServiceInsideItsOwnKeys() {
        ProblemException e = assertThrows(ProblemException.class, () -> redis.get("platform:it:" + Env.RUN + ":foreign"));
        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals("5", e.headers().get("Retry-After"));
        assertFalse(e.getMessage().contains("platform:"), "в сообщении нет имён ключей");
    }

    @Test
    void unreachableRedisGivesDependencyUnavailableQuickly() {
        RedisSettings nowhere = new RedisSettings("localhost", 1, "catalog", Duration.ofMillis(300), Duration.ofMillis(500));
        try (KitRedis broken = KitRedis.create(nowhere, RedisClientIT::password, tls)) {
            long started = System.nanoTime();
            assertFalse(broken.healthy());
            ProblemException e = assertThrows(ProblemException.class, () -> broken.get(PREFIX + "none"));
            assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
            assertEquals("5", e.headers().get("Retry-After"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(10)) < 0, "отказ быстрый, запрос не висит");
        }
    }
}
