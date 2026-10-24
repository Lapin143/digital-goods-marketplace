package dgm.platform.support.web;

import dgm.platform.support.repository.SupportRepository;
import dgm.platform.support.service.SupportOutbox;
import dgm.platform.support.service.SupportService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Подключение модуля {@code support}: ядро поддержки среза 2 (Р-137). Обработчики событий это компоненты пакета {@code handler}, потребителя
 * Kafka создаёт каркас, когда в сервисе есть хотя бы один обработчик.
 */
@Configuration(proxyBeanMethods = false)
public class SupportConfiguration {

    @Bean
    SupportService supportService(SupportRepository repository, SupportOutbox outbox, Clock clock) {
        return new SupportService(repository, outbox, clock);
    }
}
