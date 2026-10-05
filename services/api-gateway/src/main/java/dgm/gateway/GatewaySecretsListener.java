package dgm.gateway;

import dgm.kit.secret.SecretFiles;
import dgm.kit.secret.SecretProperties;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Пароль пользователя {@code gateway} в Redis берётся из файла секрета, а не из переменной окружения контейнера: клиент Redis в
 * Spring Boot принимает пароль только свойством {@code spring.data.redis.password}. Свойство создаётся в памяти процесса при старте,
 * после чтения application.yml (чтобы был виден каталог секретов) и до создания компонентов.
 */
public final class GatewaySecretsListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    static final String SOURCE_NAME = "dgmGatewaySecrets";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        String directory = environment.getProperty("dgm.secrets.dir");
        Path dir = directory == null || directory.isBlank() ? SecretFiles.directory() : Path.of(directory);
        Map<String, Object> values = SecretProperties.fromFiles(dir, Map.of("spring.data.redis.password", "redis_gateway"));
        if (!values.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, values));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }
}
