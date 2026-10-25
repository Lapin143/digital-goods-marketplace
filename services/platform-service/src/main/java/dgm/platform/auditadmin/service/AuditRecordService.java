package dgm.platform.auditadmin.service;

import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.id.Uuids;
import dgm.platform.auditadmin.domain.AuditEntry;
import dgm.platform.auditadmin.repository.AuditLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Приём записей аудита (компонент {@code audit-service}, Р-164): событие {@code audit.recorded} разбирается строго по схеме и ложится в
 * журнал одной вставкой. Вызывается обработчиком внутри транзакции потребителя: вставка и строка {@code processed_event} фиксируются вместе.
 *
 * <p>Хешей персональных полей сервис не считает: их присылает владелец данных готовыми, а здесь проверяется только форма (INV-42). Запись с
 * открытым персональным значением или формой вне схемы не повторяется, а уходит в DLQ сразу: повтор ничего не исправит, а значение
 * не должно попасть ни в журнал, ни в журнал приложения (в сообщении об отказе только имена полей).
 *
 * <p>Метрика {@code audit_records_total{result}}: {@code inserted}, {@code duplicate} (повторная доставка), {@code rejected}.
 */
public final class AuditRecordService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditRecordService.class);
    static final String METRIC = "audit_records_total";

    private final AuditLogRepository repository;
    private final Clock clock;
    private final MeterRegistry metrics;

    public AuditRecordService(AuditLogRepository repository, Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * @param eventId идентификатор события: вместе со временем действия это ключ защиты от повторной записи
     * @param data    данные события {@code audit.recorded}
     * @throws FatalEventException данные не соответствуют схеме или правилам журнала, в том числе ограничениям таблицы (DLQ без повторов)
     */
    public HandleResult record(UUID eventId, Map<String, Object> data) {
        AuditEntry entry;
        try {
            entry = AuditEntry.parse(data);
        } catch (IllegalArgumentException e) {
            count("rejected");
            LOG.warn("Событие аудита {} отклонено: {}", eventId, e.getMessage());
            throw new FatalEventException("Запись аудита не принята: " + e.getMessage(), e);
        }
        boolean inserted;
        try {
            inserted = repository.insert(Uuids.v7(clock), eventId, entry, clock.instant());
        } catch (DataIntegrityViolationException e) {
            // Ограничение таблицы строже разбора или с ним разошлось: повтор даст тот же отказ, поэтому сразу в DLQ. Текст ошибки базы
            // несёт значения строки, в сообщение он не идёт
            count("rejected");
            LOG.warn("Журнал аудита отверг запись события {} по ограничению таблицы", eventId);
            throw new FatalEventException("Журнал аудита отверг запись по ограничению таблицы", e);
        }
        if (!inserted) {
            count("duplicate");
            return HandleResult.ignored("duplicate");
        }
        count("inserted");
        return HandleResult.done();
    }

    private void count(String result) {
        metrics.counter(METRIC, "result", result).increment();
    }
}
