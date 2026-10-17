package dgm.catalog.catalog.seller.repository;

import dgm.catalog.catalog.domain.ModerationQueueItem;
import dgm.catalog.catalog.domain.ModerationQueuePage;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductPage;
import dgm.catalog.catalog.domain.ProductStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Товары продавца в схеме {@code catalog}. Все выборки ограничены профилем продавца: чужой товар не отличается от несуществующего
 * (404, ST-02). Изменения идут в транзакции вызывающего сервиса; блокировка {@link #lock} вне транзакции ничего не держит.
 */
public interface SellerProductRepository {

    /** Товар любого продавца для модератора, без блокировки. */
    Optional<Product> findById(UUID productId);

    /** Товар любого продавца для решения модератора, с блокировкой строки до конца транзакции. */
    Optional<Product> lockById(UUID productId);

    /**
     * Очередь модерации: товары {@code on_moderation}, старые выше (индекс {@code ix_product_moderation_queue}).
     *
     * @param after строго после этой пары; пусто: с начала
     * @param limit сколько строк вернуть
     */
    List<ModerationQueueItem> moderationQueue(Optional<ModerationQueuePage.After> after, int limit);

    /** Товар продавца без блокировки. */
    Optional<Product> find(UUID sellerId, UUID productId);

    /** Товар продавца с блокировкой строки до конца транзакции: два действия над одним товаром идут по очереди. */
    Optional<Product> lock(UUID sellerId, UUID productId);

    void insert(Product product);

    /**
     * Записывает новое состояние товара, если версия в базе равна {@code expectedVersion}.
     *
     * @return {@code false}, если версия в базе другая (товар изменили между чтением и записью)
     */
    boolean update(Product product, int expectedVersion);

    /**
     * Товары продавца, новые выше (индекс {@code ix_product_seller_id}).
     *
     * @param statuses пусто: все статусы
     * @param after    строго после этой пары; пусто: с начала
     * @param limit    сколько строк вернуть
     */
    List<Product> list(UUID sellerId, List<ProductStatus> statuses, Optional<ProductPage.After> after, int limit);
}
