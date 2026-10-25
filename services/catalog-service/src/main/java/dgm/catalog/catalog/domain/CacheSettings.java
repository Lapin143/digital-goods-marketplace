package dgm.catalog.catalog.domain;

import dgm.kit.config.ConfigRegistry;
import dgm.kit.config.ConfigValue;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Параметры кэша витрины (компонент {@code catalog-cache}, ADR-022). Значение из {@code application.yml} работает, пока слушатель
 * {@code platform.config} ничего не принёс; {@link #live} подключает его к реестру параметров, и дальше каждая запись карточки в Redis
 * берёт текущий срок (правило 19 руководства среза 3). Граница защищает от кэша, который либо ничего не кэширует, либо показывает давнее
 * состояние.
 *
 * <p>Ключ {@code catalog.cache-ttl-seconds} совпадает с параметром платформы и со строкой «Кэш витрины» в бюджетах времени (60 секунд).
 * До среза 3 сервис читал ключ {@code catalog.cache.product-ttl-seconds} со значением 30: расхождение найдено при подключении слушателя
 * (F4-33).
 */
public final class CacheSettings {

    public static final String PRODUCT_TTL_KEY = "catalog.cache-ttl-seconds";
    public static final long MIN_SECONDS = 1;
    public static final long MAX_SECONDS = 3600;
    private static final long DEFAULT_SECONDS = 60;

    private final Supplier<Duration> productTtl;

    /**
     * Неизменяемые настройки (значение из {@code application.yml} и тесты).
     *
     * @param productTtl срок жизни карточки в Redis, от 1 секунды до часа: так долго карточка может отставать, если сброс по изменению
     *                   не дошёл (Redis недоступен в момент сброса)
     */
    public CacheSettings(Duration productTtl) {
        if (productTtl == null || productTtl.toSeconds() < MIN_SECONDS || productTtl.toSeconds() > MAX_SECONDS) {
            throw new IllegalArgumentException("Параметр " + PRODUCT_TTL_KEY + " должен быть от 1 до 3600 секунд, а задано: " + productTtl);
        }
        this.productTtl = () -> productTtl;
    }

    private CacheSettings(ConfigValue productTtl) {
        this.productTtl = productTtl::seconds;
    }

    public static CacheSettings defaults() {
        return new CacheSettings(Duration.ofSeconds(DEFAULT_SECONDS));
    }

    /** Живые настройки: регистрирует ключ в реестре со значением {@code initial} и границами сервиса. */
    public static CacheSettings live(ConfigRegistry registry, CacheSettings initial) {
        return new CacheSettings(registry.register(PRODUCT_TTL_KEY, initial.productTtl().toSeconds(), MIN_SECONDS, MAX_SECONDS));
    }

    /** Срок жизни карточки в кэше. */
    public Duration productTtl() {
        return productTtl.get();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CacheSettings that && productTtl().equals(that.productTtl());
    }

    @Override
    public int hashCode() {
        return productTtl().hashCode();
    }

    @Override
    public String toString() {
        return "CacheSettings[productTtl=" + productTtl() + "]";
    }
}
