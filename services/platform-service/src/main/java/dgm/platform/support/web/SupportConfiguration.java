package dgm.platform.support.web;

import dgm.platform.auditadmin.api.PlatformParameters;
import dgm.platform.support.repository.SupportRepository;
import dgm.platform.support.service.SupportOutbox;
import dgm.platform.support.service.SupportRateLimiter;
import dgm.platform.support.service.SupportService;
import dgm.platform.support.service.SupportTransaction;
import dgm.platform.support.service.TicketService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Подключение модуля {@code support}: ядро поддержки среза 2 (Р-137) и обращения покупателя среза 3 (Р-177). Обработчики событий это
 * компоненты пакета {@code handler}, потребителя Kafka создаёт каркас, когда в сервисе есть хотя бы один обработчик.
 */
@Configuration(proxyBeanMethods = false)
public class SupportConfiguration {

    @Bean
    SupportService supportService(SupportRepository repository, SupportOutbox outbox, Clock clock) {
        return new SupportService(repository, outbox, clock);
    }

    @Bean
    TicketService ticketService(SupportRepository repository, SupportTransaction transaction, SupportOutbox outbox, SupportRateLimiter limiter,
                                PlatformParameters parameters, Clock clock, ObjectProvider<MeterRegistry> metrics) {
        return new TicketService(repository, transaction, outbox, limiter, parameters, clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }
}
