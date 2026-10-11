package dgm.kit.boot;

import dgm.kit.keycloak.KeycloakAdminClient;
import dgm.kit.tls.TlsMaterial;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Клиент Admin REST Keycloak (ADR-025). Включается, когда задан {@code dgm.keycloak.base-url}. Секрет клиента читается из каталога секретов
 * при каждом получении токена, Keycloak при старте сервису не нужен.
 */
@AutoConfiguration(after = KitCoreAutoConfiguration.class)
@ConditionalOnProperty(prefix = "dgm.keycloak", name = "base-url")
public class KitKeycloakAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    KeycloakAdminClient keycloakAdminClient(KitProperties properties, SecretsDirectory secrets, TlsMaterial tls, Clock clock) {
        KitProperties.Keycloak keycloak = properties.keycloak();
        return new KeycloakAdminClient(keycloak.baseUrl(), keycloak.realm(), keycloak.clientId(),
                () -> secrets.read(keycloak.clientSecret()).reveal(), tls.trustOnlyContext(), clock, keycloak.timeout());
    }
}
