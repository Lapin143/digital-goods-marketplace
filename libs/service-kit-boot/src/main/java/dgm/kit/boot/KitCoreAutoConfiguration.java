package dgm.kit.boot;

import dgm.kit.secret.SecretFiles;
import dgm.kit.time.Clocks;
import dgm.kit.tls.TlsMaterial;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Основа каркаса: настройки, часы, каталог секретов и материал TLS сервиса. Значения по умолчанию (порты, TLS, журнал JSON, метрики)
 * лежат в {@code dgm-kit-defaults.properties} и уступают {@code application.yml} сервиса; подключает их {@link KitDefaultsListener}.
 */
@AutoConfiguration
@EnableConfigurationProperties(KitProperties.class)
public class KitCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clocks.system();
    }

    @Bean
    @ConditionalOnMissingBean
    SecretsDirectory secretsDirectory(Environment environment) {
        String configured = environment.getProperty("dgm.secrets.dir");
        return new SecretsDirectory(configured == null || configured.isBlank() ? SecretFiles.directory() : Path.of(configured));
    }

    @Bean
    @ConditionalOnMissingBean
    TlsMaterial tlsMaterial(KitProperties properties, SecretsDirectory secrets) {
        return TlsMaterial.forService(secrets.path(), properties.service());
    }
}
