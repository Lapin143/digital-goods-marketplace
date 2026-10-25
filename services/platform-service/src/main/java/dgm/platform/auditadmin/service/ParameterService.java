package dgm.platform.auditadmin.service;

import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.auditadmin.domain.Administrator;
import dgm.platform.auditadmin.domain.PlatformParameter;
import dgm.platform.auditadmin.repository.ParameterRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Параметры платформы (US-8.3, FT-11.1): список и правка администратором. Правка идёт одной транзакцией: блокировка строк, проверка версии
 * ({@code If-Match}), границ и INV-07, запись значения, {@code audit.recorded} (значение до и после как есть, NFT-5.3) и {@code config.changed}
 * в Outbox. Без любого из трёх не остаётся и остальных.
 *
 * <p>INV-07: срок платёжной сессии строго короче срока резерва. Поэтому правка любого из двух параметров берёт блокировку обеих строк в
 * порядке ключей ({@code payment-session.ttl-seconds}, затем {@code reservation.ttl-seconds}) и сравнивает новое значение с текущим значением
 * пары: поднять сессию и опустить резерв проверяются одинаково, одновременные правки двух параметров идут по очереди. Триггер таблицы остаётся
 * запасным контролем, его отказ тоже даёт 422.
 *
 * <p>Метрика {@code platform_parameter_changes_total{result}}: {@code changed}, {@code unchanged} (то же значение, ничего не записано),
 * {@code conflict} (версия не совпала, 412), {@code invalid} (границы или INV-07, 422).
 */
public final class ParameterService {

    private static final Logger LOG = LoggerFactory.getLogger(ParameterService.class);
    public static final String SESSION = "payment-session.ttl-seconds";
    public static final String RESERVE = "reservation.ttl-seconds";
    /** Связанная пара INV-07 в порядке блокировки. */
    static final List<String> PAIR = List.of(SESSION, RESERVE);
    static final String METRIC = "platform_parameter_changes_total";
    /** Код действия в журнале аудита (ADR-014) и тип объекта; объект это ключ параметра. */
    static final String AUDIT_ACTION = "parameter.changed";
    static final String AUDIT_OBJECT = "parameter";

    private final ParameterRepository repository;
    private final ParameterTransaction transaction;
    private final Clock clock;
    private final MeterRegistry metrics;

    public ParameterService(ParameterRepository repository, ParameterTransaction transaction, Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.transaction = transaction;
        this.clock = clock;
        this.metrics = metrics;
    }

    public List<PlatformParameter> list() {
        return transaction.execute(repository::findAll);
    }

    /** Параметр по ключу; нет такого ключа: 404. */
    public PlatformParameter get(String key) {
        return transaction.execute(() -> repository.findAll().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow(ParameterService::notFound));
    }

    /**
     * Меняет значение параметра.
     *
     * @param expectedVersion версия из {@code If-Match}
     * @return параметр после правки; если значение то же, текущий без изменений (версия, запись аудита и событие не появляются)
     * @throws ProblemException 404 (нет ключа), 412 (версия не совпала), 422 (границы строки или INV-07)
     */
    public PlatformParameter set(Administrator admin, String key, long expectedVersion, long value) {
        Outcome outcome;
        try {
            outcome = transaction.execute(() -> change(admin, key, expectedVersion, value));
        } catch (ProblemException e) {
            count(e.type() == ProblemType.VERSION_CONFLICT ? "conflict" : e.type() == ProblemType.VALIDATION_FAILED ? "invalid" : "error");
            throw e;
        }
        count(outcome.changed() ? "changed" : "unchanged");
        return outcome.parameter();
    }

    /** Результат правки внутри транзакции: параметр и признак, что значение действительно записано. */
    private record Outcome(PlatformParameter parameter, boolean changed) {
    }

    private Outcome change(Administrator admin, String key, long expectedVersion, long value) {
        List<PlatformParameter> locked = repository.lock(PAIR.contains(key) ? PAIR : List.of(key));
        PlatformParameter current = find(locked, key).orElseThrow(ParameterService::notFound);
        if (current.version() != expectedVersion) {
            throw new ProblemException(ProblemType.VERSION_CONFLICT, "Параметр изменился, перечитайте его и повторите с новым ETag.");
        }
        List<Map<String, Object>> errors = problems(current, partner(locked, key), value);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        if (value == current.value()) {
            return new Outcome(current, false);
        }
        PlatformParameter changed;
        try {
            changed = repository.updateValue(key, value, admin.id());
        } catch (DataIntegrityViolationException e) {
            // Основная проверка выше, сюда доходит только отказ ограничения таблицы: оно строже кода или разошлось с ним
            LOG.warn("Ограничение таблицы параметров отклонило значение {}: {}", key, e.getClass().getSimpleName());
            throw invalid(List.of(error("Значение не подходит под ограничение таблицы параметров.")));
        }
        transaction.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, key, AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                AuditRecords.staffAction(clock.instant(), admin.id(), Administrator.ROLE, admin.ip(), AUDIT_ACTION, AUDIT_OBJECT, key,
                        List.of(AuditRecords.change("value", current.value(), changed.value()))));
        transaction.publish(ConfigEvents.TOPIC, ConfigEvents.AGGREGATE, key, ConfigEvents.CHANGED, ConfigEvents.SCHEMA_VERSION,
                ConfigEvents.changed(changed));
        return new Outcome(changed, true);
    }

    /**
     * Границы строки и INV-07. Нарушение границ называет сами границы; нарушение INV-07 называет парный параметр и его текущее значение,
     * чтобы администратор знал, что менять (US-8.3, сценарии 2 и 3).
     */
    private static List<Map<String, Object>> problems(PlatformParameter current, Optional<PlatformParameter> partner, long value) {
        List<Map<String, Object>> errors = new ArrayList<>();
        if (value < current.minValue() || value > current.maxValue()) {
            errors.add(error("Допустимо от " + current.minValue() + " до " + current.maxValue() + " (" + current.key() + ")."));
            return errors;
        }
        if (partner.isPresent()) {
            long other = partner.get().value();
            if (current.key().equals(SESSION) && value >= other) {
                errors.add(error("Срок платёжной сессии должен быть строго короче срока резерва: " + RESERVE + " сейчас " + other
                        + ", значение должно быть меньше " + other + " (INV-07)."));
            } else if (current.key().equals(RESERVE) && value <= other) {
                errors.add(error("Срок резерва должен быть строго длиннее срока платёжной сессии: " + SESSION + " сейчас " + other
                        + ", значение должно быть больше " + other + " (INV-07)."));
            }
        }
        return errors;
    }

    private static Optional<PlatformParameter> partner(List<PlatformParameter> locked, String key) {
        if (!PAIR.contains(key)) {
            return Optional.empty();
        }
        return find(locked, SESSION.equals(key) ? RESERVE : SESSION);
    }

    private static Optional<PlatformParameter> find(List<PlatformParameter> parameters, String key) {
        return parameters.stream().filter(p -> p.key().equals(key)).findFirst();
    }

    /**
     * Переиздаёт текущие значения всех параметров событием {@code config.changed} с их версиями (Р-167, пункт 6.3 руководства). Сжатая
     * тема хранит последнее значение каждого ключа, но после сброса одной только Kafka она пуста, а в базе значения есть: слушатели остались бы
     * со значениями из {@code application.yml}. Повтор безвреден, слушатель применяет только версию выше сохранённой. Из нескольких
     * экземпляров, стартующих вместе, переиздаёт тот, что взял консультативную блокировку.
     *
     * @return сколько значений переиздано; ноль, если блокировку держит другой экземпляр
     */
    public int reissue() {
        return transaction.execute(() -> {
            if (!repository.tryReissueLock()) {
                return 0;
            }
            List<PlatformParameter> all = repository.findAll();
            for (PlatformParameter parameter : all) {
                transaction.publish(ConfigEvents.TOPIC, ConfigEvents.AGGREGATE, parameter.key(), ConfigEvents.CHANGED, ConfigEvents.SCHEMA_VERSION,
                        ConfigEvents.changed(parameter));
            }
            return all.size();
        });
    }

    private static Map<String, Object> error(String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", "/value");
        error.put("code", "out_of_range");
        error.put("detail", detail);
        return error;
    }

    private static ProblemException invalid(List<Map<String, Object>> errors) {
        return new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                Map.of("errors", List.copyOf(errors)), Map.of());
    }

    private static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Параметр не найден.");
    }

    private void count(String result) {
        metrics.counter(METRIC, "result", result).increment();
    }
}
