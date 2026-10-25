package dgm.platform.support.limiter;

import dgm.kit.redis.KitRedis;
import dgm.kit.redis.LuaScript;
import dgm.platform.support.service.SupportRateLimiter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Скользящее окно в один час на покупателя (компонент {@code support-rate-limiter}, Р-177). Тот же скрипт Lua, что у запросов кода
 * ({@code otp-window.lua}): допуск и запись в окно неделимы, отказ ничего не расходует. Ключ под префиксом {@code platform:}, которым
 * ограничен пользователь Redis {@code platform} (ACL); время приходит параметром, поэтому тесты ведут свои часы. Недоступный Redis даёт 503
 * из клиента каркаса.
 */
@Component
public final class RedisSupportRateLimiter implements SupportRateLimiter {

    static final Duration WINDOW = Duration.ofHours(1);
    static final String KEY_PREFIX = "platform:support:requests:";
    private static final LuaScript WINDOW_SCRIPT = load("otp-window");

    private final KitRedis redis;

    public RedisSupportRateLimiter(KitRedis redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Duration> admit(UUID buyerId, int limit, Instant now) {
        Object answer = redis.eval(WINDOW_SCRIPT, List.of(KEY_PREFIX + buyerId),
                List.of(Long.toString(now.toEpochMilli()), UUID.randomUUID().toString(), "z", Long.toString(WINDOW.toMillis()), Integer.toString(limit)));
        return parse(answer instanceof String s ? s : "");
    }

    /** Ответ скрипта: {@code D:<мс>} отказ, {@code A:<осталось>:<мс>} допуск. */
    static Optional<Duration> parse(String answer) {
        if (answer.startsWith("D:")) {
            return Optional.of(Duration.ofMillis(Long.parseLong(answer.substring(2))));
        }
        if (answer.startsWith("A:")) {
            return Optional.empty();
        }
        throw new IllegalStateException("Redis вернул неожиданный ответ скрипта допуска");
    }

    /** Читает скрипт из ресурсов без строк-комментариев: Redis получает только ASCII, SHA-1 совпадает при любой кодировке соединения. */
    private static LuaScript load(String name) {
        try (InputStream in = RedisSupportRateLimiter.class.getResourceAsStream("/lua/" + name + ".lua")) {
            if (in == null) {
                throw new IllegalStateException("Нет ресурса lua/" + name + ".lua");
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            return new LuaScript(name, source, LuaScript.Result.TEXT);
        } catch (IOException e) {
            throw new UncheckedIOException("Скрипт lua/" + name + ".lua не прочитан", e);
        }
    }
}
