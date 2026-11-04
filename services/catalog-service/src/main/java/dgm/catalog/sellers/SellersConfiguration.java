package dgm.catalog.sellers;

import dgm.catalog.sellers.apikey.domain.ApiKeyValues;
import dgm.catalog.sellers.domain.SellerSettings;
import dgm.kit.config.ConfigRegistry;
import java.security.SecureRandom;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Подключение модуля {@code sellers}: параметры сроков, расписание задания контроля сроков и источник случайных байт для API-ключей. Начальные значения берутся из
 * {@code application.yml} под теми же именами, что у параметров платформы (Р-10); значение вне допустимых границ останавливает запуск
 * сервиса. Дальше параметры живые: их обновляет слушатель {@code platform.config}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SellersConfiguration {

    @Bean
    SellerSettings sellerSettings(Environment environment, ConfigRegistry registry) {
        SellerSettings defaults = SellerSettings.defaults();
        return SellerSettings.live(registry, new SellerSettings(
                seconds(environment, SellerSettings.PAUSE_KEY, defaults.reapplicationPause()),
                seconds(environment, SellerSettings.REVIEW_KEY, defaults.reviewPeriod()),
                seconds(environment, SellerSettings.LINK_TTL_KEY, defaults.documentLinkTtl())));
    }

    /** Источник случайных байт для значений API-ключей: {@link SecureRandom} потокобезопасен, один на сервис. */
    @Bean
    ApiKeyValues.RandomBytes apiKeyRandom() {
        SecureRandom secureRandom = new SecureRandom();
        return length -> {
            byte[] bytes = new byte[length];
            secureRandom.nextBytes(bytes);
            return bytes;
        };
    }

    private static Duration seconds(Environment environment, String key, Duration fallback) {
        Long value = environment.getProperty(key, Long.class);
        return value == null ? fallback : Duration.ofSeconds(value);
    }
}
