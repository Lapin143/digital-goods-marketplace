package dgm.inventory.inventory.stock.service;

import dgm.inventory.inventory.registry.ProductCopy;
import dgm.inventory.inventory.registry.ProductCopyRepository;
import dgm.inventory.inventory.service.InventoryTransaction;
import dgm.inventory.inventory.service.StockPublisher;
import dgm.inventory.inventory.stock.domain.ApiStock;
import dgm.inventory.inventory.stock.repository.ApiStockRepository;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.web.IfMatch;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Остаток товара с выдачей по API, правка продавцом ({@code setApiStock}, FT-4.3, US-4.7, ADR-008). Правила:
 *
 * <ul>
 *   <li>товар принадлежит продавцу из токена или ключа, чужой товар не отличается от несуществующего: 404;
 *   <li>способ выдачи {@code seller_api}, иначе 409 {@code issuance-method-mismatch} (Р-184); статус не «заблокирован» и не «архив»,
 *       иначе 409 {@code state-conflict};
 *   <li>правка идёт под блокировкой строки товара, как резерв и возврат, поэтому версии остатка событий идут в порядке фиксации;
 *   <li>значение заменяет остаток целиком, но только если версия записи та, что продавец видел: условный {@code UPDATE … WHERE
 *       version = :v}. Устаревшая версия это 412 {@code version-conflict} с текущим остатком в поле {@code available} и текущей версией в
 *       {@code ETag}: продавец повторяет правку по свежим данным, а заказы, пришедшие пока он смотрел форму, не затираются;
 *   <li>то же значение при верной версии ничего не меняет: версия и событие остаются прежними, повтор запроса безопасен;
 *   <li>изменение количества публикует {@code stock.changed} в той же транзакции ({@link StockPublisher}).
 * </ul>
 */
@Component
public final class ApiStockService {

    private final ProductCopyRepository products;
    private final ApiStockRepository stock;
    private final StockPublisher stockEvents;
    private final InventoryTransaction transaction;
    private final Clock clock;

    public ApiStockService(ProductCopyRepository products, ApiStockRepository stock, StockPublisher stockEvents, InventoryTransaction transaction,
                           Clock clock) {
        this.products = products;
        this.stock = stock;
        this.stockEvents = stockEvents;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Задаёт остаток своего товара.
     *
     * @param expectedVersion версия записи из {@code If-Match}
     * @param quantity        новое значение, от 0 до {@link ApiStock#MAX_QUANTITY} (границы проверяет вызывающий разбор тела)
     * @return запись после правки (или текущая, если значение не изменилось)
     * @throws ProblemException 404 нет такого товара у продавца; 409 {@code issuance-method-mismatch} или {@code state-conflict}; 412
     *                          {@code version-conflict}
     */
    public ApiStock set(UUID sellerId, UUID productId, int expectedVersion, int quantity) {
        return transaction.execute(() -> {
            ProductCopy product = products.lock(productId).filter(p -> p.ownedBy(sellerId))
                    .orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Товар не найден."));
            if (!product.sellerApi()) {
                throw new ProblemException(ProblemType.ISSUANCE_METHOD_MISMATCH, "Остаток по API задаётся только у товара со способом выдачи «по API».");
            }
            if (!product.acceptsApiStock()) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Остаток нельзя менять у товара в статусе «заблокирован» или «архив».",
                        Map.of("currentStatus", product.status()), Map.of());
            }
            stock.ensure(productId, clock.instant());
            ApiStock current = stock.find(productId).orElseThrow(() -> new IllegalStateException("Строка остатка по API не создана"));
            if (current.version() != expectedVersion) {
                throw conflict(current);
            }
            if (current.quantity() == quantity) {
                return current;
            }
            // Версия ушла между чтением и записью (тот же запрос под блокировкой товара этого не допускает, но условие в SQL надёжнее): отдаём свежие данные
            ApiStock saved = stock.replace(productId, quantity, expectedVersion).orElseThrow(() -> conflict(stock.find(productId).orElse(current)));
            stockEvents.publish(productId, saved.quantity());
            return saved;
        });
    }

    private static ProblemException conflict(ApiStock current) {
        return new ProblemException(ProblemType.VERSION_CONFLICT, "Остаток изменился, пока вы его правили: перечитайте его и повторите с новым ETag.",
                Map.of("available", current.quantity()), Map.of("ETag", IfMatch.etag(current.version())));
    }
}
