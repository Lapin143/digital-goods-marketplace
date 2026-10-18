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
     * Увеличивает версию остатка товара на единицу и возвращает новую (Р-135). Вызывается последним оператором транзакции, которая
     * меняет пул товара, при строке копии, заблокированной через {@link #lock}: тогда порядок версий равен порядку фиксации
     * изменений, и потребитель {@code stock.changed} применяет событие, только если версия больше сохранённой.
     *
     * @throws IllegalStateException копии товара нет
     */
    long bumpStockVersion(UUID productId, Instant at);

    /**
     * Меняет только статус копии (события решений модератора не несут версию товара).
     *
     * @return {@code false}: копии товара нет
     */
    boolean setStatus(UUID productId, String status, Instant at);
}
