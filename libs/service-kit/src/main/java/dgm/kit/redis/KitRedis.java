package dgm.kit.redis;

import dgm.kit.problem.ProblemException;
import dgm.kit.tls.TlsMaterial;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент Redis для временного состояния: кэш, одноразовые коды, счётчики, таймеры (ADR-010, ADR-012, ADR-022).
 *
 * <p>Соединение по TLS с проверкой сертификата и имени узла и с клиентским сертификатом сервиса, вход под пользователем ACL, пароль из
 * секрета. Операции со значениями идут скриптами Lua по SHA-1, поэтому ключи видны ACL, а составные действия (счётчик с окном)
 * атомарны; операции с отсортированными множествами (таймеры, ADR-012) это прямые команды, каждая атомарна сама по себе. Каждая
 * команда ограничена по времени; отказ, таймаут и недоступность превращаются в 503 {@code dependency-unavailable} с {@code Retry-After}:
 * для операций, которые без Redis небезопасны (отправка SMS без счётчиков), это и есть правило «Redis недоступен, действие запрещено».
 * Сервис, для которого Redis всего лишь кэш, ловит эту ошибку и работает без него.
 *
 * <p>Соединение открывается при первом обращении: сервис стартует и без Redis.
 */
public final class KitRedis implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KitRedis.class);
    private static final int RETRY_AFTER_SECONDS = 5;

    static final LuaScript GET = new LuaScript("get", "return redis.call('GET', KEYS[1])", LuaScript.Result.TEXT);
    static final LuaScript SET = new LuaScript("set", "redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2]) return 1", LuaScript.Result.INTEGER);
    static final LuaScript SET_IF_ABSENT = new LuaScript("set-if-absent",
            "if redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2], 'NX') then return 1 else return 0 end", LuaScript.Result.INTEGER);
    static final LuaScript DELETE = new LuaScript("delete", "return redis.call('DEL', KEYS[1])", LuaScript.Result.INTEGER);
    /** Счётчик с окном: окно начинается с первого увеличения и не продлевается следующими. */
    static final LuaScript INCREMENT = new LuaScript("increment",
            "local v = redis.call('INCR', KEYS[1]) if v == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end return v", LuaScript.Result.INTEGER);

    private final io.lettuce.core.RedisClient client;
    private final Object lock = new Object();
    private volatile StatefulRedisConnection<String, String> connection;

    private KitRedis(io.lettuce.core.RedisClient client) {
        this.client = client;
    }

    /**
     * @param settings адрес, пользователь и пределы времени
     * @param password пароль пользователя ACL из секрета; вызывается один раз при создании клиента
     * @param tls      сертификат сервиса и центр сертификации проекта
     */
    public static KitRedis create(RedisSettings settings, Supplier<char[]> password, TlsMaterial tls) {
        Objects.requireNonNull(settings, "settings");
        RedisURI uri = RedisURI.Builder.redis(settings.host(), settings.port())
                .withSsl(true)
                .withAuthentication(settings.user(), password.get())
                .withTimeout(settings.commandTimeout())
                .build();
        SslOptions ssl = SslOptions.builder().trustManager(tls.trustManagers()).keyManager(tls.keyManagers()).build();
        ClientOptions options = ClientOptions.builder()
                .sslOptions(ssl)
                .socketOptions(SocketOptions.builder().connectTimeout(settings.connectTimeout()).build())
                .timeoutOptions(TimeoutOptions.enabled(settings.commandTimeout()))
                .autoReconnect(true)
                // Пока связи нет, команды отклоняются сразу, а не копятся в очереди и не держат запросы пользователей
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();
        io.lettuce.core.RedisClient client = io.lettuce.core.RedisClient.create(uri);
        client.setOptions(options);
        return new KitRedis(client);
    }

    /** Значение ключа. */
    public Optional<String> get(String key) {
        Object value = eval(GET, List.of(key), List.of());
        return Optional.ofNullable((String) value);
    }

    /** Записывает значение с обязательным сроком жизни: ключ без срока в Redis проекта недопустим. */
    public void set(String key, String value, Duration ttl) {
        eval(SET, List.of(key), List.of(value, millis(ttl)));
    }

    /** Записывает значение, только если ключа нет. Возвращает {@code true}, если записано (ключ был свободен). */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return number(eval(SET_IF_ABSENT, List.of(key), List.of(value, millis(ttl)))) == 1;
    }

    /**
     * Увеличивает счётчик на единицу и возвращает новое значение. Окно {@code window} начинается с первого увеличения; последующие его
     * не продлевают, поэтому счётчик «не больше N за окно» не сдвигается самими запросами.
     */
    public long increment(String key, Duration window) {
        return number(eval(INCREMENT, List.of(key), List.of(millis(window))));
    }

    /** Удаляет ключ. Возвращает {@code true}, если ключ был. */
    public boolean delete(String key) {
        return number(eval(DELETE, List.of(key), List.of())) > 0;
    }

    // ---- Отсортированные множества (таймеры резервов, ADR-012, Р-141) ----
    // Прямые команды, а не скрипты: каждая атомарна сама по себе, а ACL проверяет ключ в самой команде. Оценка (score) это момент
    // срабатывания в миллисекундах от эпохи; целое до 2^53 хранится в Redis без потери точности. У ключа множества срока жизни нет:
    // его очищает исполнитель, а потерянные участники восстанавливает сверка по PostgreSQL.

    /**
     * Добавляет участника с оценкой или меняет оценку существующего.
     *
     * @return {@code true}, если участник новый
     */
    public boolean zadd(String key, long score, String member) {
        return direct("zadd", c -> c.zadd(key, (double) score, member)) > 0;
    }

    /**
     * Участники с оценкой от {@code minScore} до {@code maxScore} включительно, по возрастанию оценки, не больше {@code limit}.
     * Участников не удаляет: исполнитель удаляет каждого отдельно через {@link #zrem} после того, как «выиграл» его обработку.
     */
    public List<String> zrangeByScore(String key, long minScore, long maxScore, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Предел выборки должен быть не меньше 1");
        }
        List<String> members = direct("zrangebyscore",
                c -> c.zrangebyscore(key, Range.create(minScore, maxScore), Limit.create(0, limit)));
        return members == null ? List.of() : List.copyOf(members);
    }

    /**
     * Удаляет участника. Возвращает {@code true}, если он был: из нескольких экземпляров, одновременно взявших одного участника,
     * обработку продолжает тот, кто получил {@code true}.
     */
    public boolean zrem(String key, String member) {
        return direct("zrem", c -> c.zrem(key, member)) > 0;
    }

    /** Число участников. */
    public long zcard(String key) {
        return direct("zcard", c -> c.zcard(key));
    }

    private <T> T direct(String what, Function<RedisCommands<String, String>, T> operation) {
        try {
            return operation.apply(connection().sync());
        } catch (RedisException e) {
            throw unavailable("команда " + what, e);
        }
    }

    /**
     * Выполняет скрипт по SHA-1; если Redis скрипта не знает (первый вызов, перезапуск), загружает его и повторяет.
     *
     * @return строка (или {@code null}) для {@link LuaScript.Result#TEXT}, {@link Long} для {@link LuaScript.Result#INTEGER}
     * @throws ProblemException 503 {@code dependency-unavailable}, если Redis не ответил вовремя или недоступен
     */
    public Object eval(LuaScript script, List<String> keys, List<String> args) {
        ScriptOutputType type = script.result() == LuaScript.Result.INTEGER ? ScriptOutputType.INTEGER : ScriptOutputType.VALUE;
        String[] keyArray = keys.toArray(new String[0]);
        String[] argArray = args.toArray(new String[0]);
        try {
            RedisCommands<String, String> commands = connection().sync();
            try {
                return commands.evalsha(script.sha1(), type, keyArray, argArray);
            } catch (RedisNoScriptException e) {
                commands.scriptLoad(script.source());
                return commands.evalsha(script.sha1(), type, keyArray, argArray);
            }
        } catch (RedisException e) {
            throw unavailable("скрипт " + script.name(), e);
        }
    }

    /** Redis отвечает на PING. Не бросает исключений: для проверки готовности и здоровья. */
    public boolean healthy() {
        try {
            return "PONG".equalsIgnoreCase(connection().sync().ping());
        } catch (RedisException e) {
            LOG.debug("Redis не ответил на PING: {}", e.getMessage());
            return false;
        }
    }

    private StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null && current.isOpen()) {
            return current;
        }
        synchronized (lock) {
            if (connection == null || !connection.isOpen()) {
                connection = client.connect();
            }
            return connection;
        }
    }

    private static ProblemException unavailable(String what, RedisException cause) {
        // В журнал идёт класс ошибки без текста: в тексте могут оказаться адрес и имя пользователя
        LOG.warn("Redis недоступен ({}): {}", what, cause.getClass().getSimpleName());
        return ProblemException.dependencyUnavailable("Хранилище временного состояния недоступно, повторите запрос позже.", RETRY_AFTER_SECONDS);
    }

    private static String millis(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Срок жизни ключа должен быть положительным");
        }
        return Long.toString(ttl.toMillis());
    }

    private static long number(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        throw new IllegalStateException("Redis вернул не число: " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    @Override
    public void close() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
        client.shutdown(Duration.ZERO, Duration.ofSeconds(1));
    }
}
