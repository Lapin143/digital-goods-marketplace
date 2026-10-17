package dgm.catalog.catalog.cache;

import dgm.kit.redis.KitRedis;
import java.time.Duration;
import java.util.Optional;

/** {@link CacheBackend} на клиенте Redis каркаса: пользователь ACL {@code catalog} видит только ключи {@code catalog:*}. */
public final class RedisCacheBackend implements CacheBackend {

    private final KitRedis redis;

    public RedisCacheBackend(KitRedis redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> get(String key) {
        return redis.get(key);
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        redis.set(key, value, ttl);
    }

    @Override
    public void delete(String key) {
        redis.delete(key);
    }
}
