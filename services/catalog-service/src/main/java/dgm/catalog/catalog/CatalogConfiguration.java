package dgm.catalog.catalog;

import dgm.catalog.catalog.domain.ProductSettings;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Подключение модуля {@code catalog}: параметры сроков. Имя ключа то же, что у параметра платформы (Р-10), значение вне допустимых
 * границ останавливает запуск сервиса.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {

    @Bean
    ProductSettings productSettings(Environment environment) {
        Long seconds = environment.getProperty(ProductSettings.REVIEW_KEY, Long.class);
        ProductSettings defaults = ProductSettings.defaults();
        return seconds == null ? defaults : new ProductSettings(Duration.ofSeconds(seconds));
    }
}
