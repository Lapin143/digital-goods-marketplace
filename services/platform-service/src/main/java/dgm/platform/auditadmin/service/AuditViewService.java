package dgm.platform.auditadmin.service;

import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.platform.auditadmin.domain.AuditFilter;
import dgm.platform.auditadmin.domain.AuditPage;
import dgm.platform.auditadmin.domain.AuditRecordView;
import dgm.platform.auditadmin.domain.Viewer;
import dgm.platform.auditadmin.repository.AuditReadRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Чтение журнала аудита администратором (US-8.7, {@code listAuditRecords}). Чтение журнала это действие (ADR-014, Р-166): запись
 * {@code audit.viewed} уходит в Outbox и фиксируется до того, как данные уйдут клиенту, поэтому при сбое записи ответ 503 и ни одной
 * записи не отдаётся (запись до действия, а не после: так открытый журнал без следа невозможен). Запись пишется на первой странице; следующие
 * страницы того же просмотра по курсору новых записей не дают, иначе листание журнала само заполняло бы его.
 *
 * <p>В запись о просмотре не попадают ни параметры поиска, ни найденное: только кто и когда открыл журнал (roles-permissions.md, решение 4).
 *
 * <p>Метрика {@code audit_views_total{result}}: {@code recorded} (запись о просмотре зафиксирована), {@code failed} (не зафиксирована, ответ 503),
 * {@code continued} (следующая страница, записи нет).
 */
public final class AuditViewService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditViewService.class);
    static final String METRIC = "audit_views_total";
    static final String ACTION = "audit.viewed";
    static final String OBJECT_TYPE = "audit-log";
    static final String OBJECT_ID = "journal";
    private static final int RETRY_AFTER_SECONDS = 5;

    private final AuditReadRepository repository;
    private final AuditViewOutbox outbox;
    private final Clock clock;
    private final MeterRegistry metrics;

    public AuditViewService(AuditReadRepository repository, AuditViewOutbox outbox, Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.outbox = outbox;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * @throws ProblemException 503, если запись о просмотре не зафиксирована или журнал не прочитан
     */
    public AuditPage list(Viewer viewer, AuditFilter filter) {
        if (filter.after().isEmpty()) {
            recordView(viewer);
        } else {
            count("continued");
        }
        List<AuditRecordView> rows;
        try {
            rows = repository.list(filter);
        } catch (RuntimeException e) {
            // Текст ошибки базы может нести значения условий поиска, в журнал идёт только класс
            LOG.warn("Журнал аудита не прочитан: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
        boolean more = rows.size() > filter.limit();
        List<AuditRecordView> items = more ? rows.subList(0, filter.limit()) : rows;
        Optional<AuditFilter.After> next = more ? Optional.of(after(items.get(items.size() - 1))) : Optional.empty();
        return new AuditPage(items, filter.limit(), next);
    }

    private void recordView(Viewer viewer) {
        try {
            outbox.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, viewer.id().toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                    AuditRecords.staffRead(clock.instant(), viewer.id(), viewer.role(), viewer.ip(), ACTION, OBJECT_TYPE, OBJECT_ID, List.of()));
        } catch (RuntimeException e) {
            count("failed");
            LOG.warn("Запись о просмотре журнала не зафиксирована, данные не отдаются: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
        count("recorded");
    }

    private static AuditFilter.After after(AuditRecordView last) {
        return new AuditFilter.After(last.occurredAt(), last.recordId());
    }

    private static ProblemException unavailable() {
        return ProblemException.dependencyUnavailable("Журнал временно недоступен, повторите запрос позже.", RETRY_AFTER_SECONDS);
    }

    private void count(String result) {
        metrics.counter(METRIC, "result", result).increment();
    }
}
