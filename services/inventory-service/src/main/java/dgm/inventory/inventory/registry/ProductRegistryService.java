package dgm.inventory.inventory.registry;

import dgm.inventory.inventory.stock.repository.ApiStockRepository;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Реестр товаров (компонент {@code product-registry}, INV-09): ведёт {@code product_copy} по событиям каталога, чтобы внешний ключ
 * {@code key.product_id} проходил, а принадлежность товара и способ выдачи проверялись без вызова каталога.
 *
 * <p>{@code product.created} и {@code product.updated} несут версию товара: событие применяется, только если версия больше сохранённой,
 * поздний повтор старой версии ничего не портит. События решений модератора ({@code product.published}, {@code product.rejected},
 * {@code product.blocked}) версии не несут и меняют только статус копии: темы Kafka упорядочены по ключу записи (идентификатор товара),
 * поэтому они приходят после события, которое создало копию. Метод работает внутри транзакции потребителя (каркас пишет в ней же
 * {@code processed_event}).
 *
 * <p>У товара с выдачей по API (R2) вместе с копией создаётся строка остатка {@code api_stock} с нулевым количеством: продавец задаёт
 * остаток вызовом {@code setApiStock}, и до этого товар нельзя заказать (INV-09).
 */
@Component
public final class ProductRegistryService {

    private final ProductCopyRepository copies;
    private final ApiStockRepository apiStock;

    public ProductRegistryService(ProductCopyRepository copies, ApiStockRepository apiStock) {
        this.copies = copies;
        this.apiStock = apiStock;
    }

    public HandleResult apply(EventEnvelope event) {
        Instant at = event.time();
        return switch (event.type()) {
            case "product.created", "product.updated" -> copy(event, at);
            case "product.published" -> status(event, "published");
            case "product.rejected" -> status(event, "rejected");
            case "product.blocked" -> status(event, "blocked");
            default -> throw new FatalEventException("Событие " + event.type() + " реестру товаров не адресовано");
        };
    }

    /**
     * Копия товара из события. У товара с выдачей по API в той же транзакции появляется строка остатка с нулевым количеством (Р-183, ADR-008):
     * повтор события и событие с той же версией ничего не портят ({@code ON CONFLICT DO NOTHING}). Событие, которое не новее сохранённой копии,
     * строку не создаёт: её создала более новая копия.
     */
    private HandleResult copy(EventEnvelope event, Instant at) {
        ProductCopy copy = ProductEvent.copy(event.data());
        if (!copies.apply(copy, at)) {
            return HandleResult.ignored("stale-event");
        }
        if (copy.sellerApi()) {
            apiStock.ensure(copy.productId(), at);
        }
        return HandleResult.done();
    }

    private HandleResult status(EventEnvelope event, String status) {
        return copies.setStatus(ProductEvent.productId(event.data()), status, event.time())
                ? HandleResult.done() : HandleResult.ignored("unknown-product");
    }
}
