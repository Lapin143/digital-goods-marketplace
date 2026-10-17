package dgm.catalog.sellers;

import dgm.catalog.sellers.domain.SellerSettings;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Подключение модуля {@code sellers}: параметры сроков и расписание задания контроля сроков. Параметры берутся из {@code application.yml}
 * под теми же именами, что у параметров платформы (Р-10); значение вне допустимых границ останавливает запуск сервиса.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SellersConfiguration {

    @Bean
    SellerSettings sellerSettings(Environment environment) {
        SellerSettings defaults = SellerSettings.defaults();
        return new SellerSettings(
                seconds(environment, SellerSettings.PAUSE_KEY, defaults.reapplicationPause()),
                seconds(environment, SellerSettings.REVIEW_KEY, defaults.reviewPeriod()),
                seconds(environment, SellerSettings.LINK_TTL_KEY, defaults.documentLinkTtl()));
    }

    private static Duration seconds(Environment environment, String key, Duration fallback) {
        Long value = environment.getProperty(key, Long.class);
        return value == null ? fallback : Duration.ofSeconds(value);
    }
}
