package dgm.catalog.catalog.domain;

import java.time.Duration;

/**
 * Параметры кэша витрины (компонент {@code catalog-cache}, ADR-022). До слушателя {@code config.changed} (срез 3) значение читается из
 * {@code application.yml}; граница защищает от кэша, который либо ничего не кэширует, либо показывает давнее состояние.
 *
 * @param productTtl срок жизни карточки в Redis, {@code catalog.cache.product-ttl-seconds}, от 1 секунды до часа: так долго карточка
 *                   может отставать, если сброс по изменению не дошёл (Redis недоступен в момент сброса)
 */
public record CacheSettings(Duration productTtl) {

    public static final String PRODUCT_TTL_KEY = "catalog.cache.product-ttl-seconds";

    public CacheSettings {
        if (productTtl == null || productTtl.toSeconds() < 1 || productTtl.toSeconds() > 3600) {
            throw new IllegalArgumentException("Параметр " + PRODUCT_TTL_KEY + " должен быть от 1 до 3600 секунд, а задано: " + productTtl);
        }
    }

    public static CacheSettings defaults() {
        return new CacheSettings(Duration.ofSeconds(30));
    }
}
