package dgm.kit.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Скрипты Lua и настройки подключения: то, что проверяется без Redis (с Redis работает RedisClientIT). */
class LuaScriptTest {

    @Test
    void shaIsTheSha1RedisComputes() {
        // SHA-1 «return 1» хорошо известен: так Redis называет этот скрипт в SCRIPT LOAD
        assertEquals("e0e1f9fabfc9d4800c877a703b823ac0578ff8db", new LuaScript("one", "return 1", LuaScript.Result.INTEGER).sha1());
    }

    @Test
    void differentTextGivesDifferentSha() {
        assertNotEquals(KitRedis.GET.sha1(), KitRedis.SET.sha1());
        assertNotEquals(KitRedis.INCREMENT.sha1(), KitRedis.DELETE.sha1());
        assertEquals(40, KitRedis.INCREMENT.sha1().length());
    }

    @Test
    void keysAreOnlyPassedThroughKeysArray() {
        // ACL Redis ограничивает ключи шаблоном: скрипты не вычисляют имена ключей сами
        for (LuaScript script : new LuaScript[] {KitRedis.GET, KitRedis.SET, KitRedis.SET_IF_ABSENT, KitRedis.DELETE, KitRedis.INCREMENT}) {
            assertTrue(script.source().contains("KEYS[1]"), script.name());
            assertTrue(!script.source().contains("ARGV[1] .."), script.name());
        }
    }

    @Test
    void settingsRequireHostUserAndPositiveTimeouts() {
        Duration ok = Duration.ofMillis(500);
        new RedisSettings("redis", 6379, "catalog", ok, ok);
        assertThrows(IllegalArgumentException.class, () -> new RedisSettings(" ", 6379, "catalog", ok, ok));
        assertThrows(IllegalArgumentException.class, () -> new RedisSettings("redis", 6379, null, ok, ok));
        assertThrows(IllegalArgumentException.class, () -> new RedisSettings("redis", 6379, "catalog", Duration.ZERO, ok));
        assertThrows(IllegalArgumentException.class, () -> new RedisSettings("redis", 6379, "catalog", ok, Duration.ofSeconds(-1)));
    }
}
