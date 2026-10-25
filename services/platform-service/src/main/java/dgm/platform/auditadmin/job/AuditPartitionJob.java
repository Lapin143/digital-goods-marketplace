package dgm.platform.auditadmin.job;

import dgm.platform.auditadmin.service.AuditPartitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ежесуточный запуск {@link AuditPartitionService}: первый проход через полминуты после старта, потом раз в сутки от конца предыдущего.
 * Ошибка одного прохода расписание не останавливает.
 */
@Component
public final class AuditPartitionJob {

    private static final Logger LOG = LoggerFactory.getLogger(AuditPartitionJob.class);

    private final AuditPartitionService service;

    public AuditPartitionJob(AuditPartitionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${dgm.audit.partition-interval:PT24H}", initialDelayString = "${dgm.audit.partition-initial-delay:PT30S}")
    public void run() {
        try {
            service.run();
        } catch (RuntimeException e) {
            LOG.error("Секции журнала аудита не обновлены: {}", e.getClass().getSimpleName(), e);
        }
    }
}
