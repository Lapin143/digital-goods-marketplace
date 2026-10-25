package dgm.catalog.catalog;

import dgm.catalog.catalog.cache.ProductCache;
import dgm.catalog.catalog.cache.RedisCacheBackend;
import dgm.catalog.catalog.domain.CacheSettings;
import dgm.catalog.catalog.domain.ProductSettings;
import dgm.kit.config.ConfigRegistry;
import dgm.kit.redis.KitRedis;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Подключение модуля {@code catalog}: параметры сроков и кэш витрины. Имя ключа то же, что у параметра платформы (Р-10), значение вне допустимых
 * границ останавливает запуск сервиса.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {

    /** Срок рассмотрения товара {@code moderation.review-deadline-seconds}: живой, его обновляет слушатель параметров платформы. */
    @Bean
    ProductSettings productSettings(Environment environment, ConfigRegistry registry) {
        Long seconds = environment.getProperty(ProductSettings.REVIEW_KEY, Long.class);
        ProductSettings defaults = ProductSettings.defaults();
        return ProductSettings.live(registry, seconds == null ? defaults : new ProductSettings(Duration.ofSeconds(seconds)));
    }

    /** Срок жизни карточки в кэше витрины: {@code catalog.cache-ttl-seconds}, по умолчанию 60 секунд; живой, как и срок рассмотрения. */
    @Bean
    CacheSettings cacheSettings(Environment environment, ConfigRegistry registry) {
        Long seconds = environment.getProperty(CacheSettings.PRODUCT_TTL_KEY, Long.class);
        return CacheSettings.live(registry, seconds == null ? CacheSettings.defaults() : new CacheSettings(Duration.ofSeconds(seconds)));
    }

    /** Кэш на Redis каркаса; без {@code dgm.redis.host} кэш выключен и карточка читается из базы. */
    @Bean(destroyMethod = "close")
    ProductCache productCache(ObjectProvider<KitRedis> redis, CacheSettings settings, MeterRegistry meters) {
        KitRedis client = redis.getIfAvailable();
        return new ProductCache(client == null ? null : new RedisCacheBackend(client), settings, meters);
    }
}
