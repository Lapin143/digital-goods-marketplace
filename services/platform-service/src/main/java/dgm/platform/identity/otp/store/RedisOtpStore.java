package dgm.platform.identity.otp.store;

import dgm.kit.json.Json;
import dgm.kit.redis.KitRedis;
import dgm.kit.redis.LuaScript;
import dgm.platform.identity.otp.service.OtpStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Одноразовые коды и счётчики запросов в Redis (ADR-010, компонент {@code otp-service}). Каждое составное действие один скрипт Lua
 * ({@code src/main/resources/lua}): допуск запроса сразу по всем окнам и проверка кода вместе с погашением неделимы. Все ключи под префиксом
 * {@code platform:}, которым ограничен пользователь Redis {@code platform} (ACL). Время приходит параметром: команда {@code TIME} этому
 * пользователю закрыта, а тесты ведут свои часы. Недоступный Redis даёт 503 из клиента каркаса: без счётчиков SMS не уходит.
 */
@Component
public final class RedisOtpStore implements OtpStore {

    private static final LuaScript WINDOW = load("otp-window");
    private static final LuaScript REDEEM = load("otp-redeem");

    private final KitRedis redis;

    public RedisOtpStore(KitRedis redis) {
        this.redis = redis;
    }

    @Override
    public Admission admit(List<Window> windows, Instant now) {
        List<String> keys = new ArrayList<>(windows.size());
        List<String> args = new ArrayList<>(2 + 3 * windows.size());
        args.add(Long.toString(now.toEpochMilli()));
        args.add(UUID.randomUUID().toString());
        for (Window window : windows) {
            keys.add(window.key());
            args.add(window.counter() ? "c" : "z");
            args.add(Long.toString(window.span().toMillis()));
            args.add(Integer.toString(window.limit()));
        }
        return parseAdmission(text(redis.eval(WINDOW, keys, args)));
    }

    @Override
    public void put(String key, Code code, Duration retention) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("h", code.hash());
        record.put("x", code.expiresAt().toEpochMilli());
        record.put("a", code.attempts());
        record.put("u", code.userId());
        record.put("c", code.confirmationId());
        record.put("p", code.phone());
        redis.set(key, Json.write(record), retention);
    }

    @Override
    public void remove(String key) {
        redis.delete(key);
    }

    @Override
    public Redemption redeem(Redeem request, Instant now) {
        Optional<Tracking> tracking = request.tracking();
        List<String> keys = tracking.map(t -> List.of(request.codeKey(), t.burnKey(), t.lockKey()))
                .orElseGet(() -> List.of(request.codeKey(), request.codeKey(), request.codeKey()));
        List<String> args = List.of(
                Long.toString(now.toEpochMilli()),
                request.hash(),
                request.confirmationId(),
                tracking.isPresent() ? "1" : "0",
                Integer.toString(tracking.map(Tracking::burnLimit).orElse(0)),
                Long.toString(tracking.map(t -> t.burnWindow().toMillis()).orElse(0L)),
                Long.toString(tracking.map(t -> t.lockFor().toMillis()).orElse(0L)));
        return parseRedemption(text(redis.eval(REDEEM, keys, args)));
    }

    @Override
    public Optional<Duration> lockedFor(String lockKey, Instant now) {
        return redis.get(lockKey).map(Long::parseLong).map(until -> until - now.toEpochMilli()).filter(ms -> ms > 0).map(Duration::ofMillis);
    }

    @Override
    public boolean markOnce(String key, Duration ttl) {
        return redis.setIfAbsent(key, "1", ttl);
    }

    // ---- разбор ответов скриптов ----

    static Admission parseAdmission(String answer) {
        if (answer.startsWith("D:")) {
            return Admission.denied(Duration.ofMillis(Long.parseLong(answer.substring(2))));
        }
        String[] parts = answer.split(":");
        if (parts.length == 3 && parts[0].equals("A")) {
            return Admission.allowed(Integer.parseInt(parts[1]), Duration.ofMillis(Long.parseLong(parts[2])));
        }
        throw new IllegalStateException("Redis вернул неожиданный ответ скрипта допуска");
    }

    static Redemption parseRedemption(String answer) {
        switch (answer) {
            case "N":
                return Redemption.of(Result.ABSENT);
            case "M":
                return Redemption.of(Result.OTHER);
            case "E":
                return Redemption.of(Result.EXPIRED);
            default:
                break;
        }
        if (answer.startsWith("K:")) {
            return new Redemption(Result.LOCKED, "", "", 0, Duration.ofMillis(Long.parseLong(answer.substring(2))));
        }
        if (answer.startsWith("L:")) {
            return new Redemption(Result.EXHAUSTED, "", "", 0, Duration.ofMillis(Long.parseLong(answer.substring(2))));
        }
        if (answer.startsWith("I:")) {
            return new Redemption(Result.INVALID, "", "", Integer.parseInt(answer.substring(2)), Duration.ZERO);
        }
        if (answer.startsWith("V:")) {
            String[] parts = answer.split(":", 3);
            if (parts.length == 3) {
                return new Redemption(Result.VALID, parts[1], parts[2], 0, Duration.ZERO);
            }
        }
        throw new IllegalStateException("Redis вернул неожиданный ответ скрипта проверки кода");
    }

    private static String text(Object value) {
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalStateException("Redis вернул не строку");
    }

    /**
     * Читает скрипт из ресурсов без строк-комментариев: Redis получает только ASCII, поэтому SHA-1, посчитанный у нас, совпадает с
     * подсчитанным Redis при любой кодировке соединения.
     */
    private static LuaScript load(String name) {
        try (InputStream in = RedisOtpStore.class.getResourceAsStream("/lua/" + name + ".lua")) {
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
