package dgm.catalog.catalog.cache;

import java.time.Duration;
import java.util.Optional;

/** Хранилище кэша: в сервисе это Redis, в модульных тестах память. Отказ хранилища это исключение, кэш его перехватывает. */
public interface CacheBackend {

    Optional<String> get(String key);

    void set(String key, String value, Duration ttl);

    void delete(String key);
}
