package dgm.inventory.inventory.registry;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Копии товаров каталога в {@code inventory.product_copy}. */
public interface ProductCopyRepository {

    Optional<ProductCopy> find(UUID productId);

    /** Копия под блокировкой строки до конца транзакции: загрузки одного товара идут по очереди (предел пула, дубли). */
    Optional<ProductCopy> lock(UUID productId);

    /**
     * Создаёт или обновляет копию, если версия события больше сохранённой.
     *
     * @return {@code false}: версия не новее сохранённой, событие устарело
     */
    boolean apply(ProductCopy copy, Instant at);

    /**
     * Меняет только статус копии (события решений модератора не несут версию товара).
     *
     * @return {@code false}: копии товара нет
     */
    boolean setStatus(UUID productId, String status, Instant at);
}
