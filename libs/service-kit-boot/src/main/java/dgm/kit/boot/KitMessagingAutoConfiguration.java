package dgm.kit.boot;

import dgm.kit.config.ConfigListener;
import dgm.kit.config.ConfigRegistry;
import dgm.kit.config.KafkaConfigFeed;
import dgm.kit.events.DlqSink;
import dgm.kit.events.EventConsumer;
import dgm.kit.events.EventHandler;
import dgm.kit.events.EventProcessor;
import dgm.kit.events.KafkaDlqSink;
import dgm.kit.kafka.KafkaClients;
import dgm.kit.outbox.EventPublisher;
import dgm.kit.outbox.KafkaEventPublisher;
import dgm.kit.outbox.OutboxRelay;
import dgm.kit.outbox.OutboxSettings;
import dgm.kit.tls.TlsMaterial;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

/**
 * Сообщения: публикатор Outbox (ADR-005) и потребитель событий (ADR-006, ADR-011). Включаются, когда заданы {@code dgm.kafka.bootstrap}
 * и база. Потребитель стартует только если в сервисе есть обработчики {@link EventHandler}: без них читать темы незачем, а
 * подтверждённые без обработки события потерялись бы. Права в Kafka выданы по CN сертификата сервиса.
 */
@AutoConfiguration(after = {KitCoreAutoConfiguration.class, KitDatabaseAutoConfiguration.class})
@ConditionalOnProperty(prefix = "dgm.kafka", name = "bootstrap")
@ConditionalOnBean(ModuleDatabases.class)
public class KitMessagingAutoConfiguration {

    @Bean(destroyMethod = "close")
    EventPublisher eventPublisher(KitProperties properties, TlsMaterial tls) {
        return KafkaEventPublisher.create(properties.kafka().bootstrap(), tls.kafkaProperties(), properties.service());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "dgm.outbox", name = "enabled", matchIfMissing = true)
    OutboxRelay outboxRelay(KitProperties properties, ModuleDatabases databases, Modules modules, EventPublisher publisher, Clock clock,
                            ObjectProvider<MeterRegistry> metrics) {
        return new OutboxRelay(databases.dedicated(modules.defaultModule(), "outbox-relay", 1), publisher, properties.service(),
                OutboxSettings.defaults(), clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(EventHandler.class)
    @ConditionalOnProperty(prefix = "dgm.consumer", name = "enabled", matchIfMissing = true)
    DlqSink dlqSink(KitProperties properties, TlsMaterial tls) {
        return new KafkaDlqSink(KafkaClients.producer(properties.kafka().bootstrap(), tls.kafkaProperties(), properties.service() + "-dlq"),
                Duration.ofSeconds(25));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnBean(EventHandler.class)
    @ConditionalOnProperty(prefix = "dgm.consumer", name = "enabled", matchIfMissing = true)
    EventConsumer eventConsumer(KitProperties properties, Modules modules, TlsMaterial tls, List<EventHandler> handlers, DlqSink dlq,
                                Clock clock, ObjectProvider<MeterRegistry> metrics) {
        String consumerModule = properties.consumer().module() == null || properties.consumer().module().isBlank()
                ? modules.defaultModule() : properties.consumer().module();
        // Ошибка в имени модуля обработчика должна остановить старт, а не первое событие
        for (EventHandler handler : handlers) {
            modules.transactions(handler.module() == null ? consumerModule : handler.module());
        }
        EventProcessor processor = new EventProcessor(properties.service(), handlers, modules::processedEvents, modules::transactions,
                consumerModule, dlq, EventProcessor.DEFAULT_RETRY_DELAYS, clock, duration -> Thread.sleep(duration),
                metrics.getIfAvailable(SimpleMeterRegistry::new));
        return new EventConsumer(KafkaClients.consumer(properties.kafka().bootstrap(), tls.kafkaProperties(), properties.service(),
                properties.service() + "-consumer"), processor, Duration.ofMillis(500), Duration.ofSeconds(1));
    }

    /** Тема параметров платформы: сжатая, одна на все сервисы ({@code platform.config}). */
    private static final String CONFIG_TOPIC = "platform.config";

    /**
     * Слушатель параметров (Р-168): собственный поток и собственный потребитель без группы, чтение сжатой темы с начала. Выключается
     * {@code dgm.config.enabled=false}: так делает {@code platform-service} (у него параметры в собственной базе, права на чтение
     * темы нет) и интеграционные тесты сервисов, чтобы значения из общей темы стенда не подменяли значения теста. Поток стартует после
     * запуска приложения ({@link #configListenerStart}), когда все сервисы уже зарегистрировали свои ключи: сообщение по ещё не
     * зарегистрированному ключу было бы отклонено и не пришло бы повторно.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "dgm.config", name = "enabled", matchIfMissing = true)
    ConfigListener configListener(KitProperties properties, TlsMaterial tls, ConfigRegistry registry, ObjectProvider<MeterRegistry> metrics) {
        String bootstrap = properties.kafka().bootstrap();
        String clientId = properties.service() + "-config";
        return new ConfigListener(() -> new KafkaConfigFeed(KafkaClients.configConsumer(bootstrap, tls.kafkaProperties(), clientId), CONFIG_TOPIC),
                registry, metrics.getIfAvailable(SimpleMeterRegistry::new), Duration.ofMillis(500), Duration.ofSeconds(5));
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> configListenerStart(ObjectProvider<ConfigListener> listener) {
        return event -> listener.ifAvailable(ConfigListener::start);
    }
}
