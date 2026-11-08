package dgm.platform.notification.web;

import dgm.kit.boot.PeerClients;
import dgm.kit.boot.SecretsDirectory;
import dgm.kit.client.EndpointGuard;
import dgm.kit.client.SellerCaller;
import dgm.kit.crypto.SellerSecrets;
import dgm.kit.tls.TlsMaterial;
import dgm.platform.identity.api.UserContacts;
import dgm.platform.notification.client.HttpEmailClient;
import dgm.platform.notification.client.SellerEndpointClient;
import dgm.platform.notification.client.WebhookClient;
import dgm.platform.notification.api.DirectMail;
import dgm.platform.notification.domain.RetryPolicy;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.repository.NotificationRepository;
import dgm.platform.notification.repository.WebhookDeliveryRepository;
import dgm.platform.notification.service.DirectMailService;
import dgm.platform.notification.service.EmailSender;
import dgm.platform.notification.service.NotificationDispatcher;
import dgm.platform.notification.service.NotificationProperties;
import dgm.platform.notification.service.NotificationService;
import dgm.platform.notification.service.NotificationTransaction;
import dgm.platform.notification.service.ReceiveMailStatusService;
import dgm.platform.notification.service.WebhookDeliveryService;
import dgm.platform.notification.service.WebhookDispatcher;
import dgm.platform.notification.service.WebhookProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Подключение модуля {@code notification}: шаблоны писем, клиент провайдера почты (ключ {@code platform_email_key} из секретов), постановка
 * в очередь, отправитель по расписанию и приём статусов писем от провайдера. Обработчики событий это компоненты пакета {@code handler},
 * потребителя Kafka запускает каркас, когда в сервисе есть хотя бы один обработчик. Адрес провайдера на стенде заглушка
 * {@code external-stubs}, в проде адрес настоящего провайдера (ключ {@code dgm.email.base-url}).
 *
 * <p>Webhook продавцам (срез 1 Ф5, Р-193): отправитель {@link WebhookDispatcher} с вызовом системы продавца через {@link SellerCaller} (защита адреса от
 * SSRF, подпись, тайм-аут 5 секунд), адрес и версия секрета из {@code catalog-service} по mTLS, секрет подписи считается из мастер-секрета
 * {@code seller_webhook_master} и не хранится (Р-187), список доставок продавца.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NotificationProperties.class, WebhookProperties.class})
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

    /** Статусы писем платформы от провайдера: вебхук {@code receiveMailStatus} (Р-171). */
    @Bean
    ReceiveMailStatusService receiveMailStatusService(NotificationRepository repository, NotificationTransaction transaction, Clock clock,
                                                      ObjectProvider<MeterRegistry> metrics) {
        return new ReceiveMailStatusService(repository, transaction, clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Мастер-секрет, из которого считаются секреты подписи webhook продавцам (Р-187). Нет файла или он короче 16 байт: сервис не запускается. */
    static final String SELLER_MASTER_SECRET = "seller_webhook_master";
    /** Имена, освобождённые от проверки диапазонов адресов продавца: только стендовый профиль (ADR-028, правило 7). */
    static final String SELLER_ENDPOINT_ALLOW = "dgm.seller-endpoint.allow";

    @Bean
    SellerSecrets sellerSecrets(SecretsDirectory secrets) {
        return new SellerSecrets(secrets.read(SELLER_MASTER_SECRET));
    }

    /** Защита адреса продавца от SSRF: список разрешённых имён пуст, кроме стенда с заглушкой во внутренней сети. */
    @Bean
    EndpointGuard endpointGuard(Environment environment) {
        return EndpointGuard.system(allowedNames(environment.getProperty(SELLER_ENDPOINT_ALLOW, "")));
    }

    static Set<String> allowedNames(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(name -> !name.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Вызов системы продавца: проверка адреса, подпись, тайм-аут 5 секунд. Доверие к серверу продавца по центру платформы (на стенде заглушка подписана им);
     * для продавцов с публичными сертификатами нужно доверие хранилищу JVM (ограничение стенда, F5-21).
     */
    @Bean
    SellerCaller sellerCaller(EndpointGuard guard, TlsMaterial tls, Clock clock) {
        return SellerCaller.over(guard, tls.trustOnlyContext().getSocketFactory(), clock);
    }

    @Bean
    WebhookClient webhookClient(SellerCaller caller) {
        return new WebhookClient(caller);
    }

    /** Клиент {@code catalog-service} (mTLS, {@code dgm.peers.catalog-service}): создаётся при первом вызове, поэтому платформа стартует и без каталога. */
    @Bean
    SellerEndpointClient sellerEndpointClient(PeerClients peers, Clock clock) {
        return new SellerEndpointClient(() -> peers.get(SellerEndpointClient.PEER), clock);
    }

    /** Отправитель webhook продавцам: паузы повторов 30 секунд, 2, 10, 30 минут, 2 часа (Р-193), затем статус {@code failed}. */
    @Bean
    WebhookDispatcher webhookDispatcher(WebhookDeliveryRepository deliveries, SellerEndpointClient endpoints, WebhookClient client, SellerSecrets secrets,
                                        WebhookProperties properties, Clock clock, ObjectProvider<MeterRegistry> metrics) {
        return new WebhookDispatcher(deliveries, endpoints, client, secrets, new RetryPolicy(properties.retryDelays()), properties.lease(), properties.batch(),
                clock, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Список доставок webhook продавца: {@code listWebhookDeliveries} (OP-102). */
    @Bean
    WebhookDeliveryService webhookDeliveryService(WebhookDeliveryRepository deliveries) {
        return new WebhookDeliveryService(deliveries);
    }
}
