package dgm.platform.notification.web;

import dgm.kit.boot.SecretsDirectory;
import dgm.kit.tls.TlsMaterial;
import dgm.platform.identity.api.UserContacts;
import dgm.platform.notification.client.HttpEmailClient;
import dgm.platform.notification.api.DirectMail;
import dgm.platform.notification.domain.RetryPolicy;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.repository.NotificationRepository;
import dgm.platform.notification.service.DirectMailService;
import dgm.platform.notification.service.EmailSender;
import dgm.platform.notification.service.NotificationDispatcher;
import dgm.platform.notification.service.NotificationProperties;
import dgm.platform.notification.service.NotificationService;
import dgm.platform.notification.service.NotificationTransaction;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Подключение модуля {@code notification}: шаблоны писем, клиент провайдера почты (ключ {@code platform_email_key} из секретов), постановка
 * в очередь и отправитель по расписанию. Обработчики событий это компоненты пакета {@code handler}, потребителя Kafka запускает каркас,
 * когда в сервисе есть хотя бы один обработчик. Адрес провайдера на стенде заглушка {@code external-stubs}, в проде адрес настоящего
 * провайдера (ключ {@code dgm.email.base-url}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfiguration {

    @Bean
    Letters letters(NotificationProperties properties) {
        return new Letters(ZoneId.of(properties.zone()), properties.zoneLabel(), properties.siteUrl());
    }

    @Bean
    EmailSender emailSender(SecretsDirectory secrets, TlsMaterial tls, @Value("${dgm.email.base-url:https://external-stubs:8443}") String baseUrl) {
        return new HttpEmailClient(baseUrl, () -> secrets.read("platform_email_key"), tls.trustOnlyContext());
    }

    /** Прямая отправка писем с кодом (Р-169): минуя очередь, код в базу не попадает. */
    @Bean
    DirectMail directMail(Letters letters, EmailSender sender, Clock clock, ObjectProvider<MeterRegistry> metrics) {
        return new DirectMailService(letters, sender, clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    NotificationService notificationService(NotificationRepository repository, Letters letters, Clock clock) {
        return new NotificationService(repository, letters, clock);
    }

    @Bean
    NotificationDispatcher notificationDispatcher(NotificationRepository repository, UserContacts contacts, Letters letters, EmailSender sender,
                                                  NotificationTransaction transaction, NotificationProperties properties, Clock clock,
                                                  ObjectProvider<MeterRegistry> metrics) {
        return new NotificationDispatcher(repository, contacts, letters, sender, transaction, new RetryPolicy(properties.retryDelays()),
                properties.lease(), properties.batch(), clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }
}
