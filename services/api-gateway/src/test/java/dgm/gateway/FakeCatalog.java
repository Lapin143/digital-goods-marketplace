package dgm.gateway;

import dgm.kit.trace.TraceContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** {@code checkApiKey} каталога для тестов шлюза: знает выпущенные ключи по хешу, считает вызовы, умеет «не отвечать». */
final class FakeCatalog implements ApiKeyVerifier.CatalogCheck {

    static final ApiKeyVerifier.CatalogAnswer UNKNOWN = new ApiKeyVerifier.CatalogAnswer(false, null, null, null);

    private final Map<String, ApiKeyVerifier.CatalogAnswer> known = new ConcurrentHashMap<>();
    final AtomicInteger calls = new AtomicInteger();
    final List<String> hashes = new CopyOnWriteArrayList<>();
    volatile boolean down;
    /** Если задан, отвечает вместо таблицы (медленный ответ, особая ошибка). */
    volatile Supplier<Mono<ApiKeyVerifier.CatalogAnswer>> override;

    @Override
    public Mono<ApiKeyVerifier.CatalogAnswer> check(String keyHash, TraceContext trace) {
        calls.incrementAndGet();
        hashes.add(keyHash);
        Supplier<Mono<ApiKeyVerifier.CatalogAnswer>> custom = override;
        if (custom != null) {
            return custom.get();
        }
        if (down) {
            return Mono.error(new IllegalStateException("catalog-service не отвечает"));
        }
        return Mono.just(known.getOrDefault(keyHash, UNKNOWN));
    }

    /** Возвращает каталог в исходное состояние (для заглушки, общей на все тесты класса). */
    void reset() {
        known.clear();
        calls.set(0);
        hashes.clear();
        down = false;
        override = null;
    }

    /** Ключ выпущен: действителен, профиль продавца в данном статусе. */
    FakeCatalog issue(String value, String apiKeyId, String sellerId, String sellerStatus) {
        known.put(ApiKeyVerifier.hash(value), new ApiKeyVerifier.CatalogAnswer(true, apiKeyId, sellerId, sellerStatus));
        return this;
    }

    /** Ключ отозван: каталог отвечает как про неизвестный. */
    FakeCatalog revoke(String value) {
        known.put(ApiKeyVerifier.hash(value), UNKNOWN);
        return this;
    }
}
