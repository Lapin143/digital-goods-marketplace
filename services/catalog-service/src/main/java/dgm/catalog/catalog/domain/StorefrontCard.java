package dgm.catalog.catalog.domain;

import java.util.List;
import java.util.UUID;

/**
 * Карточка опубликованного товара для витрины (схема {@code StorefrontProduct}). {@code available} берётся из копии остатка
 * {@code catalog.stock_view} и может отставать на доли секунды (NFT-1.0); товар без строки в копии показывается как «нет в наличии».
 */
public record StorefrontCard(UUID id, UUID sellerId, String title, String description, String productType, String platform,
                             List<String> activationRegions, long price, String currency, int available) {

    /** Предел количества в одном заказе (INV-10). */
    public static final int MAX_ORDER_QUANTITY = 10;

    public boolean inStock() {
        return available > 0;
    }

    /** Сколько штук можно заказать сейчас: от 0 до 10, ноль значит «нет в наличии» (INV-09, INV-10). */
    public int maxOrderQuantity() {
        return Math.min(available, MAX_ORDER_QUANTITY);
    }
}
