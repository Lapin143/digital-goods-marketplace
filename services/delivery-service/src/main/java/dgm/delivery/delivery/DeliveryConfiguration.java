package dgm.delivery.delivery;

import dgm.delivery.delivery.client.EmailClient;
import dgm.delivery.delivery.client.HttpEmailClient;
import dgm.delivery.delivery.client.KeyClient;
import dgm.delivery.delivery.client.PeerKeyClient;
import dgm.delivery.delivery.client.SellerApiClient;
import dgm.delivery.delivery.client.SellerCalls;
import dgm.delivery.delivery.client.SellerEndpointClient;
import dgm.delivery.delivery.client.SellerEndpoints;
import dgm.delivery.delivery.client.StatusClient;
import dgm.delivery.delivery.dispatcher.DeliveryDispatcher;
import dgm.delivery.delivery.dispatcher.SellerRequestJob;
import dgm.delivery.delivery.dispatcher.SellerTimeoutJob;
import dgm.delivery.delivery.domain.DeliverySchedule;
import dgm.delivery.delivery.letter.LetterBuilder;
import dgm.delivery.delivery.repository.DeliveryRepository;
import dgm.delivery.delivery.repository.SellerRequestRepository;
import dgm.delivery.delivery.service.DeliveryIntake;
import dgm.delivery.delivery.service.DeliveryOutcomes;
import dgm.delivery.delivery.service.DeliveryRepeat;
import dgm.delivery.delivery.poller.StatusPoller;
import dgm.delivery.delivery.service.DeliverySettings;
import dgm.delivery.delivery.service.DeliveryStatuses;
import dgm.delivery.delivery.service.DeliveryTransaction;
import dgm.delivery.delivery.service.SellerRequestOutcomes;
import dgm.delivery.delivery.service.SellerSettings;
import dgm.delivery.delivery.service.StatusSettings;
import dgm.delivery.delivery.watch.DeliveryWatch;
import dgm.kit.boot.PeerClients;
import dgm.kit.boot.SecretsDirectory;
import dgm.kit.client.EndpointGuard;
import dgm.kit.client.SellerCaller;
import dgm.kit.crypto.SellerSecrets;
import dgm.kit.tls.TlsMaterial;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Подключение модуля выдачи: расписание повторов (параметр платформы {@code delivery.retry-schedule}, FT-11.1), настройки диспетчера и контроля,
 * клиент ключей ({@code inventory-service} в {@code dgm.peers}), клиент почты (адрес {@code dgm.email.base-url}, ключ в секрете
 * {@code delivery_email_key}), сборщик письма, приём оплаты и повторная выдача по смене адреса, диспетчер, применение статусов писем (вебхук и опрос) и контроль 30 минут. Обработчики событий это компоненты пакета {@code events}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class DeliveryConfiguration {

    /** Секрет с ключом доступа к e-mail-провайдеру. */
    static final String EMAIL_KEY_SECRET = "delivery_email_key";
    /** Мастер-секрет, из которого считаются секреты подписи запросов продавцам (ADR-028, Р-187). */
    static final String SELLER_MASTER_SECRET = "seller_webhook_master";
    /** Имена, освобождённые от проверки диапазонов адресов продавца: только стендовый профиль (ADR-028, правило 7). */
    static final String SELLER_ENDPOINT_ALLOW = "dgm.seller-endpoint.allow";

    /** Паузы повторов: ключ {@code delivery.retry-schedule}, по умолчанию 10 с, 30 с, 2, 5, 10 минут ±20 %. */
    @Bean
    DeliverySchedule deliverySchedule(Environment environment) {
        return DeliverySchedule.parse(environment.getProperty("delivery.retry-schedule", "10s,30s,2m,5m,10m"), DeliverySchedule.JITTER,
                RandomGenerator.getDefault());
    }

    @Bean
    DeliverySettings deliverySettings(Environment environment) {
        DeliverySettings defaults = DeliverySettings.defaults();
        long controlMinutes = environment.getProperty("delivery.control-minutes", Long.class, defaults.controlWindow().toMinutes());
        return new DeliverySettings(
                environment.getProperty("dgm.delivery.dispatcher.lease", Duration.class, defaults.lease()),
                environment.getProperty("dgm.delivery.dispatcher.batch", Integer.class, defaults.batch()),
                Duration.ofMinutes(controlMinutes));
    }

    @Bean
    KeyClient keyClient(PeerClients peers) {
        return new PeerKeyClient(() -> peers.get(PeerKeyClient.PEER));
    }

    /** Один клиент провайдера для отправки письма и для запроса статуса (опрос). */
    @Bean
    HttpEmailClient emailClient(SecretsDirectory secrets, TlsMaterial tls, @Value("${dgm.email.base-url:https://external-stubs:8443}") String baseUrl) {
        return new HttpEmailClient(baseUrl, () -> secrets.read(EMAIL_KEY_SECRET), tls.trustOnlyContext());
    }

    @Bean
    StatusSettings statusSettings(Environment environment) {
        StatusSettings defaults = StatusSettings.defaults();
        return new StatusSettings(
                environment.getProperty("dgm.delivery.poller.min-age", Duration.class, defaults.pollAfter()),
                environment.getProperty("dgm.delivery.poller.batch", Integer.class, defaults.pollBatch()),
                environment.getProperty("dgm.delivery.watch.batch", Integer.class, defaults.watchBatch()));
    }

    @Bean
    LetterBuilder letterBuilder() {
        return new LetterBuilder();
    }

    @Bean
    DeliveryIntake deliveryIntake(DeliveryRepository deliveries, SellerRequestRepository sellerRequests, SellerSettings sellerSettings,
                                  DeliverySettings settings, Clock clock) {
        return new DeliveryIntake(deliveries, sellerRequests, sellerSettings, settings, clock);
    }

    @Bean
    DeliveryRepeat deliveryRepeat(DeliveryRepository deliveries, Clock clock) {
        return new DeliveryRepeat(deliveries, clock);
    }

    @Bean
    DeliveryOutcomes deliveryOutcomes(DeliveryRepository deliveries, DeliveryTransaction transaction, DeliverySchedule schedule, DeliverySettings settings,
                                      Clock clock, MeterRegistry meters) {
        return new DeliveryOutcomes(deliveries, transaction, schedule, settings, clock, meters);
    }

    @Bean
    DeliveryStatuses deliveryStatuses(DeliveryRepository deliveries, DeliveryTransaction transaction, DeliverySettings settings, Clock clock,
                                      MeterRegistry meters) {
        return new DeliveryStatuses(deliveries, transaction, settings, clock, meters);
    }

    @Bean
    StatusPoller statusPoller(DeliveryRepository deliveries, StatusClient provider, DeliveryStatuses statuses, StatusSettings settings, Clock clock,
                              MeterRegistry meters) {
        return new StatusPoller(deliveries, provider, statuses, settings, clock, meters);
    }

    @Bean
    DeliveryWatch deliveryWatch(DeliveryRepository deliveries, DeliveryTransaction transaction, DeliverySettings settings, StatusSettings statusSettings,
                                Clock clock, MeterRegistry meters) {
        return new DeliveryWatch(deliveries, transaction, settings, statusSettings, clock, meters);
    }

    @Bean
    DeliveryDispatcher deliveryDispatcher(DeliveryRepository deliveries, KeyClient keys, LetterBuilder letters, EmailClient email, DeliveryOutcomes outcomes,
                                          DeliverySettings settings, Clock clock, MeterRegistry meters) {
        return new DeliveryDispatcher(deliveries, keys, letters, email, outcomes, settings, clock, meters);
    }

    /** Выдача по запросу к системе продавца (ADR-028, шаг 12): окно ответа {@code delivery.seller-response-seconds}, паузы и пачки заданы архитектурой. */
    @Bean
    SellerSettings sellerSettings(Environment environment) {
        SellerSettings defaults = SellerSettings.defaults();
        long responseSeconds = environment.getProperty("delivery.seller-response-seconds", Long.class, defaults.response().toSeconds());
        return new SellerSettings(Duration.ofSeconds(responseSeconds), defaults.sendLease(), defaults.answerLease(),
                environment.getProperty("dgm.delivery.seller.batch", Integer.class, defaults.batch()),
                environment.getProperty("dgm.delivery.seller.timeout-batch", Integer.class, defaults.timeoutBatch()));
    }

    /** Мастер-секрет читается один раз при запуске; нет файла или он короче 16 байт, сервис не запускается. Уничтожать его нельзя: он нужен на всё время работы. */
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
    SellerApiClient sellerApiClient(SellerCaller caller) {
        return new SellerApiClient(caller);
    }

    @Bean
    SellerEndpointClient sellerEndpointClient(PeerClients peers, Clock clock) {
        return new SellerEndpointClient(() -> peers.get(SellerEndpointClient.PEER), clock);
    }

    @Bean
    SellerRequestOutcomes sellerRequestOutcomes(SellerRequestRepository requests, DeliveryRepository deliveries, DeliveryTransaction transaction,
                                                SellerSettings settings, Clock clock, MeterRegistry meters) {
        return new SellerRequestOutcomes(requests, deliveries, transaction, settings, clock, meters);
    }

    @Bean
    SellerRequestJob sellerRequestJob(SellerRequestRepository requests, SellerEndpoints endpoints, SellerCalls api, SellerSecrets secrets,
                                      SellerRequestOutcomes outcomes, SellerSettings settings, Clock clock) {
        return new SellerRequestJob(requests, endpoints, api, secrets, outcomes, settings, clock);
    }

    @Bean
    SellerTimeoutJob sellerTimeoutJob(SellerRequestOutcomes outcomes) {
        return new SellerTimeoutJob(outcomes);
    }
}
