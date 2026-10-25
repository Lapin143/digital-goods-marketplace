package dgm.platform.auditadmin.service;

import dgm.platform.auditadmin.repository.AuditLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Секции журнала аудита (Р-164, правило 15): месячные секции создаются на три месяца вперёд, а в страховочной секции считаются строки.
 * Строки там быть не должно: они значат, что запись пришла со временем действия вне созданных месяцев (часы сервиса-издателя ушли или
 * секции не создавались). Запись при этом не теряется, но её нужно перенести вручную, поэтому значение метрики {@code audit_default_rows}
 * выше нуля это повод для оповещения.
 *
 * <p>Секции создаёт функция владельца схемы под ролью записи, два экземпляра сервиса не мешают друг другу: блокировку берёт сам запрос.
 */
public final class AuditPartitionService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditPartitionService.class);
    /** Сколько месяцев вперёд, считая текущий, должно быть секций. */
    public static final int MONTHS = 4;
    static final String GAUGE = "audit_default_rows";

    private final AuditLogRepository repository;
    private final Clock clock;
    private final AtomicLong defaultRows = new AtomicLong();

    public AuditPartitionService(AuditLogRepository repository, Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.clock = clock;
        metrics.gauge(GAUGE, defaultRows);
    }

    /**
     * Один проход: секции с текущего месяца по UTC и ещё на три месяца вперёд, затем счёт строк страховочной секции.
     *
     * @return сколько секций проверено или создано (ноль: блокировку держит другой экземпляр)
     */
    public int run() {
        LocalDate first = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        int ensured = repository.ensurePartitions(first, MONTHS);
        long rows = repository.defaultPartitionRows();
        defaultRows.set(rows);
        if (rows > 0) {
            LOG.warn("В страховочной секции журнала аудита {} строк: время действия вне созданных месяцев, строки нужно перенести в месячные секции", rows);
        }
        if (ensured > 0) {
            LOG.info("Секции журнала аудита проверены: {} месяцев начиная с {}", ensured, first);
        }
        return ensured;
    }
}
