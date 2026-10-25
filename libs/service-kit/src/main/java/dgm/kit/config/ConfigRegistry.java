package dgm.kit.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Реестр параметров, которые читает сервис (Р-168). Сервис регистрирует свои ключи со значением из {@code application.yml} и границами,
 * получает {@link ConfigValue} и читает его при каждом обращении; слушатель {@link ConfigListener} передаёт сюда сообщения
 * {@code config.changed}.
 *
 * <p>Сообщение применяется, только если ключ известен сервису, не закреплён, версия строго выше сохранённой, значение в границах и не
 * нарушает INV-07 (срок платёжной сессии строго короче срока резерва) в паре с текущим значением соседа. Всё остальное отклоняется
 * с причиной и счётчиком {@code config_rejected_total{key,reason}}; отклонённое сообщение не двигает версию и не роняет сервис.
 *
 * <p>Сосед по INV-07 в сравнении участвует, только если его значение уже пришло из темы (версия выше 0). Сжатая тема хранит последнее
 * значение каждого ключа в порядке записи, и при чтении с начала первым может прийти значение, которое было допустимо относительно
 * более раннего значения соседа: сравнение со значением из настроек отклонило бы его зря. Допустимость пары гарантирует платформа, она
 * проверяет её при каждой правке под блокировкой обеих строк.
 *
 * <p>Метрики: {@code config_version{key}}, {@code config_applied_total{key}}, {@code config_rejected_total{key,reason}},
 * {@code config_pinned_total{key}}, {@code config_ignored_total{key}} (ключ другого сервиса: тема общая, это штатный случай).
 */
public final class ConfigRegistry {

    /** Что стало с сообщением. */
    public enum Outcome {
        APPLIED, DUPLICATE, STALE, PINNED, UNKNOWN, OUT_OF_RANGE, INVARIANT
    }

    private static final Logger LOG = LoggerFactory.getLogger(ConfigRegistry.class);

    /** INV-07: значение {@code shorter} меньше значения {@code longer} не меньше чем на {@code gap} (не меньше 1: строго меньше). */
    private record Pair(String shorter, String longer, long gap) {
    }

    private final Map<String, ConfigValue> values = new ConcurrentHashMap<>();
    private final List<Pair> pairs = new ArrayList<>();
    private final Set<String> pinned;
    private final MeterRegistry metrics;

    /**
     * @param pinnedKeys закреплённые ключи ({@code dgm.config.pinned-keys}): сообщения по ним не применяются; список непуст только в
     *                   профиле ускоренного времени для проверок (Р-175)
     */
    public ConfigRegistry(Collection<String> pinnedKeys, MeterRegistry metrics) {
        this.pinned = Set.copyOf(pinnedKeys);
        this.metrics = metrics;
    }

    public Set<String> pinnedKeys() {
        return pinned;
    }

    /**
     * Регистрирует параметр сервиса.
     *
     * @param defaultValue значение из {@code application.yml}; работает, пока нет сообщений, и при недоступной Kafka
     * @param min          наименьшее допустимое значение
     * @param max          наибольшее допустимое значение
     * @throws IllegalStateException ключ уже зарегистрирован или значение из настроек вне границ
     */
    public synchronized ConfigValue register(String key, long defaultValue, long min, long max) {
        if (values.containsKey(key)) {
            throw new IllegalStateException("Параметр " + key + " уже зарегистрирован");
        }
        ConfigValue value = new ConfigValue(key, defaultValue, min, max);
        values.put(key, value);
        Gauge.builder("config_version", value, ConfigValue::version).tag("key", key).register(metrics);
        return value;
    }

    /**
     * Параметр, который читают несколько модулей одного сервиса (например срок рассмотрения в заявках и в товарах): первый вызов
     * регистрирует его, следующие возвращают то же значение. Границы и значение из настроек у всех обращений обязаны совпадать.
     *
     * @throws IllegalStateException ключ уже зарегистрирован с другими границами или другим значением из настроек
     */
    public synchronized ConfigValue obtain(String key, long defaultValue, long min, long max) {
        ConfigValue existing = values.get(key);
        if (existing == null) {
            return register(key, defaultValue, min, max);
        }
        if (existing.defaultValue() != defaultValue || existing.min() != min || existing.max() != max) {
            throw new IllegalStateException("Параметр " + key + " уже зарегистрирован с другими границами или значением из настроек");
        }
        return existing;
    }

    /**
     * Требует INV-07 для пары: значение {@code shorter} строго меньше значения {@code longer}. Значения из настроек проверяются сразу,
     * сообщения слушателя при каждом применении.
     *
     * @throws IllegalStateException ключ не зарегистрирован или значения из настроек нарушают соотношение (сервис не должен стартовать)
     */
    public synchronized void requireShorter(String shorter, String longer) {
        requireShorter(shorter, longer, 1);
    }

    /**
     * То же с запасом: {@code shorter + margin <= longer}. Запас меньше 1 означает строгое неравенство (равные сроки оставляют покупателю
     * оплату в ту секунду, когда резерв снимается).
     */
    public synchronized void requireShorter(String shorter, String longer, long margin) {
        ConfigValue first = require(shorter);
        ConfigValue second = require(longer);
        long gap = Math.max(1, margin);
        if (first.get() + gap > second.get()) {
            throw new IllegalStateException("INV-07: " + shorter + " (" + first.get() + ") должен быть короче " + longer + " (" + second.get()
                    + ") не меньше чем на " + gap);
        }
        pairs.add(new Pair(shorter, longer, gap));
    }

    public Optional<ConfigValue> find(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /** Применяет значение из сообщения; см. описание класса. Вызывается потоком слушателя, читатели значений не блокируются. */
    public synchronized Outcome apply(String key, long value, int version) {
        ConfigValue target = values.get(key);
        if (target == null) {
            // чужой ключ: тема общая на все сервисы, и у каждого сервиса большая часть ключей не его; это не отказ и не повод для тревоги
            metrics.counter("config_ignored_total", "key", key).increment();
            return Outcome.UNKNOWN;
        }
        if (pinned.contains(key)) {
            metrics.counter("config_pinned_total", "key", key).increment();
            return Outcome.PINNED;
        }
        if (version == target.version()) {
            return reject(key, Outcome.DUPLICATE, "duplicate");
        }
        if (version < target.version()) {
            return reject(key, Outcome.STALE, "stale-config");
        }
        if (value < target.min() || value > target.max()) {
            LOG.warn("Значение {} = {} (версия {}) вне границ {} – {}, не применено", key, value, version, target.min(), target.max());
            return reject(key, Outcome.OUT_OF_RANGE, "out-of-range");
        }
        String broken = invariantBroken(key, value);
        if (broken != null) {
            LOG.warn("Значение {} = {} (версия {}) нарушает INV-07: {}, не применено", key, value, version, broken);
            return reject(key, Outcome.INVARIANT, "inv-07");
        }
        long before = target.get();
        target.set(value, version);
        metrics.counter("config_applied_total", "key", key).increment();
        LOG.info("Параметр {} = {} (было {}, версия {})", key, value, before, version);
        return Outcome.APPLIED;
    }

    /** Описание нарушенной пары или {@code null}. */
    private String invariantBroken(String key, long value) {
        for (Pair pair : pairs) {
            if (pair.shorter().equals(key)) {
                ConfigValue neighbour = values.get(pair.longer());
                if (neighbour.version() > 0 && value + pair.gap() > neighbour.get()) {
                    return key + " должен быть короче " + pair.longer() + " (" + neighbour.get() + ") не меньше чем на " + pair.gap();
                }
            } else if (pair.longer().equals(key)) {
                ConfigValue neighbour = values.get(pair.shorter());
                if (neighbour.version() > 0 && neighbour.get() + pair.gap() > value) {
                    return key + " должен быть длиннее " + pair.shorter() + " (" + neighbour.get() + ") не меньше чем на " + pair.gap();
                }
            }
        }
        return null;
    }

    private Outcome reject(String key, Outcome outcome, String reason) {
        metrics.counter("config_rejected_total", "key", key, "reason", reason).increment();
        return outcome;
    }

    private ConfigValue require(String key) {
        ConfigValue value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Параметр " + key + " не зарегистрирован");
        }
        return value;
    }
}
