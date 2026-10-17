package dgm.catalog.catalog.stock;

import dgm.catalog.catalog.cache.ProductCache;
import dgm.catalog.catalog.repository.StockViewRepository;
import dgm.catalog.catalog.service.CatalogTransaction;
import dgm.kit.events.HandleResult;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Представление остатка для витрины (компонент {@code stock-view-service}, INV-09, FT-4.3, FT-4.4, Р-15). Источник истины остатка это
 * {@code inventory-service}; здесь только копия числа свободных ключей, по которой карточка решает «в наличии» или «нет в наличии» и
 * сколько штук можно заказать.
 *
 * <p>Событие применяется, если его метка не меньше сохранённой: после возврата из недоставленных старое событие не откатывает остаток.
 * Метод работает внутри транзакции потребителя события (каркас пишет в ней же {@code processed_event}); кэш карточки сбрасывается после
 * фиксации и не в этой транзакции.
 */
@Component
public final class StockViewService {

    private final StockViewRepository repository;
    private final CatalogTransaction transaction;
    private final ProductCache cache;

    public StockViewService(StockViewRepository repository, CatalogTransaction transaction, ProductCache cache) {
        this.repository = repository;
        this.transaction = transaction;
        this.cache = cache;
    }

    public HandleResult apply(StockChange change, Instant eventTime) {
        StockViewRepository.Outcome outcome = repository.apply(change.productId(), change.available(), eventTime);
        return switch (outcome) {
            case APPLIED -> {
                transaction.afterCommit(() -> cache.evictLater(change.productId()));
                yield HandleResult.done();
            }
            case STALE -> HandleResult.ignored("stale-event");
            case UNKNOWN_PRODUCT -> HandleResult.ignored("unknown-product");
        };
    }
}
