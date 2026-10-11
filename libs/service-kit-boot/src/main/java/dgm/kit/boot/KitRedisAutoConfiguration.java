package dgm.kit.boot;

import dgm.kit.redis.KitRedis;
import dgm.kit.redis.RedisSettings;
import dgm.kit.tls.TlsMaterial;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Клиент Redis (ADR-022). Включается, когда в сервисе есть Lettuce и задан {@code dgm.redis.host}. Пользователь ACL берётся из
 * {@code dgm.redis.user}, его пароль из секрета {@code redis_<пользователь>}. Соединение открывается при первом обращении, поэтому
 * сервис стартует и без Redis; состояние Redis видно в метрике {@code redis_up}.
 */
@AutoConfiguration(after = KitCoreAutoConfiguration.class)
@ConditionalOnClass(name = "io.lettuce.core.RedisClient")
@ConditionalOnProperty(prefix = "dgm.redis", name = "host")
public class KitRedisAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    KitRedis kitRedis(KitProperties properties, SecretsDirectory secrets, TlsMaterial tls) {
        KitProperties.Redis redis = properties.redis();
        if (redis.user() == null || redis.user().isBlank()) {
            throw new IllegalStateException("Не задан пользователь Redis dgm.redis.user");
        }
        RedisSettings settings = new RedisSettings(redis.host(), redis.port(), redis.user(), redis.commandTimeout(), redis.connectTimeout());
        return KitRedis.create(settings, () -> secrets.read("redis_" + redis.user()).reveal().toCharArray(), tls);
    }

    /** 1, если Redis отвечает на PING, иначе 0. Значение считается при сборе метрик. */
    @Bean
    MeterBinder redisUpMeter(KitRedis redis) {
        return registry -> Gauge.builder("redis_up", redis, r -> r.healthy() ? 1.0 : 0.0)
                .description("Redis отвечает на PING")
                .register(registry);
    }
}
