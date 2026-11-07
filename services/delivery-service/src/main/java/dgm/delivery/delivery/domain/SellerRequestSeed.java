package dgm.delivery.delivery.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Данные заказа с выдачей по API из {@code order.paid}, из которых рождается запрос продавцу: продавец, товар и короткий номер заказа.
 * Покупателя и адреса здесь нет, в запрос продавцу они не идут (INV-44).
 */
public record SellerRequestSeed(UUID sellerId, UUID productId, long orderNumber) {

    public SellerRequestSeed {
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(productId, "productId");
        if (orderNumber < 1001) {
            throw new IllegalArgumentException("Номер заказа начинается с 1001");
        }
    }
}
