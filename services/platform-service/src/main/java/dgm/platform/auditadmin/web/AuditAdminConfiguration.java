package dgm.platform.auditadmin.web;

import dgm.platform.auditadmin.repository.AuditLogRepository;
import dgm.platform.auditadmin.service.AuditPartitionService;
import dgm.platform.auditadmin.service.AuditRecordService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Подключение модуля {@code audit_admin}: приём журнала аудита (Р-164) и обслуживание его секций. Обработчик {@code audit-consumer} и задание
 * секций это компоненты своих пакетов, потребителя Kafka создаёт каркас.
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
}
