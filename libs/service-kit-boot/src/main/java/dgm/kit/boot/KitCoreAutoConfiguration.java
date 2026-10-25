package dgm.kit.boot;

import dgm.kit.config.ConfigRegistry;
import dgm.kit.secret.SecretFiles;
import dgm.kit.time.Clocks;
import dgm.kit.tls.TlsMaterial;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
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

    /**
     * Реестр параметров платформы, которые читает сервис (Р-168). Есть всегда: сервис регистрирует в нём свои ключи со значениями из
     * {@code application.yml}, а слушатель {@code platform.config} (при доступной Kafka) обновляет их. Закреплённые ключи
     * ({@code dgm.config.pinned-keys}) слушатель не применяет; список непуст только в профиле ускоренного времени (Р-175).
     */
    @Bean
    @ConditionalOnMissingBean
    ConfigRegistry configRegistry(Environment environment, ObjectProvider<MeterRegistry> metrics) {
        List<String> pinned = Binder.get(environment).bind("dgm.config.pinned-keys", Bindable.listOf(String.class)).orElse(List.of());
        return new ConfigRegistry(pinned, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    @ConditionalOnMissingBean
    TlsMaterial tlsMaterial(KitProperties properties, SecretsDirectory secrets) {
        return TlsMaterial.forService(secrets.path(), properties.service());
    }
}
