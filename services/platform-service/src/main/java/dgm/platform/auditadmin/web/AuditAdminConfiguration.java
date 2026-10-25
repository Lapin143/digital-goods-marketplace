package dgm.platform.auditadmin.web;

import dgm.platform.auditadmin.repository.AuditLogRepository;
import dgm.platform.auditadmin.repository.AuditReadRepository;
import dgm.platform.auditadmin.service.AuditPartitionService;
import dgm.platform.auditadmin.service.AuditRecordService;
import dgm.platform.auditadmin.service.AuditViewOutbox;
import dgm.platform.auditadmin.service.AuditViewService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Подключение модуля {@code audit_admin}: приём журнала аудита (Р-164), обслуживание его секций и чтение журнала администратором (Р-166).
 * Обработчик {@code audit-consumer} и задание секций это компоненты своих пакетов, потребителя Kafka создаёт каркас.
 */
@Configuration(proxyBeanMethods = false)
public class AuditAdminConfiguration {

    @Bean
    AuditRecordService auditRecordService(AuditLogRepository repository, Clock clock, ObjectProvider<MeterRegistry> metrics) {
        return new AuditRecordService(repository, clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    AuditPartitionService auditPartitionService(AuditLogRepository repository, Clock clock, ObjectProvider<MeterRegistry> metrics) {
        return new AuditPartitionService(repository, clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    AuditViewService auditViewService(AuditReadRepository repository, AuditViewOutbox outbox, Clock clock, ObjectProvider<MeterRegistry> metrics) {
        return new AuditViewService(repository, outbox, clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }
}
