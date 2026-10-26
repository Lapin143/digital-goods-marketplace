package dgm.platform.support.web;

import dgm.kit.boot.PeerClients;
import dgm.platform.auditadmin.api.PlatformParameters;
import dgm.platform.identity.api.UserContacts;
import dgm.platform.support.client.OrderClient;
import dgm.platform.support.client.PeerOrderClient;
import dgm.platform.support.repository.SupportRepository;
import dgm.platform.support.service.QueueMetrics;
import dgm.platform.support.service.QueueService;
import dgm.platform.support.service.ResendService;
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
 * Подключение модуля {@code support}: ядро поддержки среза 2 (Р-137), обращения покупателя, очередь оператора и повторная отправка ключа среза 3 (Р-177, Р-163, Р-170). Обработчики событий это
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

    @Bean
    QueueService queueService(SupportRepository repository, SupportTransaction transaction, SupportOutbox outbox, Clock clock,
                              ObjectProvider<MeterRegistry> metrics) {
        return new QueueService(repository, transaction, outbox, clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Клиент {@code order-service} (mTLS, {@code dgm.peers.order-service}): создаётся при первом вызове, поэтому платформа стартует и без заказов. */
    @Bean
    OrderClient orderClient(PeerClients peers) {
        return new PeerOrderClient(() -> peers.get(PeerOrderClient.PEER));
    }

    @Bean
    ResendService resendService(SupportRepository repository, SupportTransaction transaction, SupportOutbox outbox, UserContacts contacts, OrderClient orders,
                                Clock clock, ObjectProvider<MeterRegistry> metrics) {
        return new ResendService(repository, transaction, outbox, contacts, orders, clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    QueueMetrics queueMetrics(SupportRepository repository, Clock clock, ObjectProvider<MeterRegistry> metrics) {
        return new QueueMetrics(repository, clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }
}
