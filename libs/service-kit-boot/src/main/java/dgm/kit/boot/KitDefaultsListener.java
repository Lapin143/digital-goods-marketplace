package dgm.kit.boot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;

/**
 * Подключает значения каркаса по умолчанию ({@code dgm-kit-defaults.properties}) с самым низким приоритетом: {@code application.yml}
 * сервиса, переменные окружения и параметры запуска их переопределяют. Работает до настройки журнала и до чтения самого приложения,
 * поэтому в файле можно задавать и формат журнала, и порты. Порядок: после загрузчика {@code application.yml}
 * ({@code HIGHEST_PRECEDENCE + 10}) и до настройки журнала ({@code HIGHEST_PRECEDENCE + 20}).
 */
public final class KitDefaultsListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    static final String SOURCE_NAME = "dgmKitDefaults";
    static final String RESOURCE = "dgm-kit-defaults.properties";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        apply(event.getEnvironment());
    }

    static void apply(ConfigurableEnvironment environment) {
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(SOURCE_NAME)) {
            return;
        }
        sources.addLast(new PropertiesPropertySource(SOURCE_NAME, load()));
    }

    static Properties load() {
        try (InputStream in = KitDefaultsListener.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Нет файла значений по умолчанию " + RESOURCE);
            }
            Properties properties = new Properties();
            properties.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException("Файл значений по умолчанию не читается: " + RESOURCE, e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }
}
