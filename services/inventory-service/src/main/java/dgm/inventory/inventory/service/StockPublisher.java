package dgm.inventory.inventory.service;

import dgm.inventory.inventory.registry.ProductCopyRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Событие {@code stock.changed} (Р-135, ADR-012): число свободных ключей товара после изменения и версия остатка. Версия это счётчик
 * {@code product_copy.stock_version}, который растёт на единицу в последнем операторе каждой транзакции, меняющей пул товара
 * (загрузка, резерв, подтверждение, снятие). Строка товара при этом заблокирована с начала транзакции, поэтому порядок версий равен
 * порядку фиксации изменений, а не порядку меток времени, и потребитель применяет событие, только если версия больше сохранённой.
 *
 * <p>Вызывается внутри транзакции модуля, которая изменила пул: версия и запись в Outbox фиксируются вместе с изменением.
 */
@Component
public final class StockPublisher {

    /** Тема, агрегат и версия схемы события остатка (контракт событий). */
    static final String TOPIC = "inventory.events";
    static final String AGGREGATE = "ProductStock";
    static final String STOCK_CHANGED = "stock.changed";
    static final int SCHEMA_VERSION = 1;

    private final ProductCopyRepository products;
    private final InventoryTransaction transaction;
    private final Clock clock;

    public StockPublisher(ProductCopyRepository products, InventoryTransaction transaction, Clock clock) {
        this.products = products;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Увеличивает версию остатка и пишет {@code stock.changed} в Outbox текущей транзакции.
     *
     * @param available число свободных ключей товара после изменения (абсолютное значение, повтор события безопасен)
     * @return версия остатка, записанная в событие
     */
    public long publish(UUID productId, int available) {
        long version = products.bumpStockVersion(productId, clock.instant());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", productId.toString());
        data.put("available", available);
        data.put("version", version);
        transaction.publish(TOPIC, AGGREGATE, productId.toString(), STOCK_CHANGED, SCHEMA_VERSION, data);
        return version;
    }
}
