package dgm.catalog.sellers.integration;

import dgm.catalog.sellers.integration.crypto.IntegrationSecrets;
import dgm.kit.boot.SecretsDirectory;
import dgm.kit.client.EndpointGuard;
import dgm.kit.crypto.SellerSecrets;
import dgm.kit.secret.SecretValue;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Подключение системы продавца: защита адреса от SSRF и секрет подписи. Мастер-секрет {@code seller_webhook_master} читается из каталога секретов
 * один раз при запуске; нет файла или он короче 16 байт, сервис не запускается (тихой работы без секрета подписи быть не должно).
 *
 * <p>Список имён, освобождённых от проверки диапазонов адресов, {@code dgm.seller-endpoint.allow}, пуст по умолчанию и задаётся только стендовым
 * профилем, где заглушка продавца живёт во внутренней сети (ADR-028, правило 7); значения идут через запятую.
 */
@Configuration(proxyBeanMethods = false)
public class IntegrationConfiguration {

    static final String ALLOW_PROPERTY = "dgm.seller-endpoint.allow";
    static final String MASTER_SECRET = "seller_webhook_master";

    @Bean
    EndpointGuard endpointGuard(Environment environment) {
        return EndpointGuard.system(allowed(environment.getProperty(ALLOW_PROPERTY, "")));
    }

    @Bean
    IntegrationSecrets integrationSecrets(SecretsDirectory directory) {
        // Мастер-секрет остаётся у SellerSecrets на всё время работы сервиса: уничтожать его здесь нельзя
        SecretValue master = directory.read(MASTER_SECRET);
        return new IntegrationSecrets(new SellerSecrets(master));
    }

    static Set<String> allowed(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(name -> !name.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
}
