package dgm.catalog.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Товар в выдаче витрины. {@code inStock} берётся из копии остатка {@code catalog.stock_view} и может отставать на доли секунды
 * (NFT-1.0); товара без строки в копии показывается как «нет в наличии».
 */
public record StorefrontItem(UUID id, String title, String productType, String platform, long price, String currency, boolean inStock,
                             Instant publishedAt) {
}
