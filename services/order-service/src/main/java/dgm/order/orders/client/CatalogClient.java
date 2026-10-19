package dgm.order.orders.client;

import dgm.kit.client.DependencyUnavailableException;
import java.util.Optional;
import java.util.UUID;

/**
 * Клиент каталога (адаптер, {@code catalog-client}): «карточка товара» для оформления заказа (SEQ-01, шаги 4 и 5). Цена берётся отсюда, а не из
 * запроса покупателя. Тайм-аут попытки 500 мс, один повтор (c4-components-order-service.md).
 */
public interface CatalogClient {

    /**
     * @return карточка или пусто, если каталог не знает такого товара (404)
     * @throws DependencyUnavailableException каталог не ответил
     */
    Optional<ProductCard> card(UUID productId);

    /**
     * Карточка товара для заказа.
     *
     * @param status         статус товара: продавать можно только {@code published}
     * @param price          цена за единицу в копейках
     * @param issuanceMethod способ выдачи: в R1 продаётся только {@code key_pool}
     */
    record ProductCard(UUID productId, UUID sellerId, String title, String status, long price, String currency, String issuanceMethod) {
    }
}
