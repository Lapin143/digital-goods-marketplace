package dgm.catalog.catalog.cache;

import dgm.catalog.catalog.domain.CacheSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Хранилище кэша в памяти для модульных тестов: запоминает записи и сроки, умеет «отказывать» как недоступный Redis. */
public final class MemoryCache implements CacheBackend {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();
    private boolean down;
    private int reads;

    /** Кэш поверх этого хранилища; сброс выполняется сразу, в потоке вызова. */
    public ProductCache cache() {
        return new ProductCache(this, CacheSettings.defaults(), new SimpleMeterRegistry(), Runnable::run);
    }

    /** Хранилище недоступно: каждое обращение бросает исключение. */
    public MemoryCache down(boolean value) {
        this.down = value;
        return this;
    }

    public boolean has(java.util.UUID productId) {
        return values.containsKey(ProductCache.PREFIX + productId);
    }

    public Duration ttl(java.util.UUID productId) {
        return ttls.get(ProductCache.PREFIX + productId);
    }

    public int reads() {
        return reads;
    }

    @Override
    public Optional<String> get(String key) {
        check();
        reads++;
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        check();
        values.put(key, value);
        ttls.put(key, ttl);
    }

    @Override
    public void delete(String key) {
        check();
        values.remove(key);
        ttls.remove(key);
    }

    private void check() {
        if (down) {
            throw new IllegalStateException("Redis недоступен");
        }
    }
}
