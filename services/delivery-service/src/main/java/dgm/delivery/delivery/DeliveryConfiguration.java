package dgm.delivery.delivery;

import dgm.delivery.delivery.client.EmailClient;
import dgm.delivery.delivery.client.HttpEmailClient;
import dgm.delivery.delivery.client.KeyClient;
import dgm.delivery.delivery.client.PeerKeyClient;
import dgm.delivery.delivery.dispatcher.DeliveryDispatcher;
import dgm.delivery.delivery.domain.DeliverySchedule;
import dgm.delivery.delivery.letter.LetterBuilder;
import dgm.delivery.delivery.repository.DeliveryRepository;
import dgm.delivery.delivery.service.DeliveryIntake;
import dgm.delivery.delivery.service.DeliveryOutcomes;
import dgm.delivery.delivery.service.DeliverySettings;
import dgm.delivery.delivery.service.DeliveryTransaction;
import dgm.kit.boot.PeerClients;
import dgm.kit.boot.SecretsDirectory;
import dgm.kit.tls.TlsMaterial;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Подключение модуля выдачи: расписание повторов (параметр платформы {@code delivery.retry-schedule}, FT-11.1), настройки диспетчера и контроля,
 * клиент ключей ({@code inventory-service} в {@code dgm.peers}), клиент почты (адрес {@code dgm.email.base-url}, ключ в секрете
 * {@code delivery_email_key}), сборщик письма и диспетчер. Обработчики событий это компоненты пакета {@code events}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class DeliveryConfiguration {

    /** Секрет с ключом доступа к e-mail-провайдеру. */
    static final String EMAIL_KEY_SECRET = "delivery_email_key";

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

    @Bean
    EmailClient emailClient(SecretsDirectory secrets, TlsMaterial tls, @Value("${dgm.email.base-url:https://external-stubs:8443}") String baseUrl) {
        return new HttpEmailClient(baseUrl, () -> secrets.read(EMAIL_KEY_SECRET), tls.trustOnlyContext());
    }

    @Bean
    LetterBuilder letterBuilder() {
        return new LetterBuilder();
    }

    @Bean
    DeliveryIntake deliveryIntake(DeliveryRepository deliveries, DeliverySettings settings, Clock clock) {
        return new DeliveryIntake(deliveries, settings, clock);
    }

    @Bean
    DeliveryOutcomes deliveryOutcomes(DeliveryRepository deliveries, DeliveryTransaction transaction, DeliverySchedule schedule, Clock clock,
                                      MeterRegistry meters) {
        return new DeliveryOutcomes(deliveries, transaction, schedule, clock, meters);
    }

    @Bean
    DeliveryDispatcher deliveryDispatcher(DeliveryRepository deliveries, KeyClient keys, LetterBuilder letters, EmailClient email, DeliveryOutcomes outcomes,
                                          DeliverySettings settings, Clock clock, MeterRegistry meters) {
        return new DeliveryDispatcher(deliveries, keys, letters, email, outcomes, settings, clock, meters);
    }
}
