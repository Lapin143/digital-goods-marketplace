package dgm.inventory.inventory.registry;

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
 */
@Component
public final class ProductRegistryService {

    private final ProductCopyRepository copies;

    public ProductRegistryService(ProductCopyRepository copies) {
        this.copies = copies;
    }

    public HandleResult apply(EventEnvelope event) {
        Instant at = event.time();
        return switch (event.type()) {
            case "product.created", "product.updated" -> copies.apply(ProductEvent.copy(event.data()), at)
                    ? HandleResult.done() : HandleResult.ignored("stale-event");
            case "product.published" -> status(event, "published");
            case "product.rejected" -> status(event, "rejected");
            case "product.blocked" -> status(event, "blocked");
            default -> throw new FatalEventException("Событие " + event.type() + " реестру товаров не адресовано");
        };
    }

    private HandleResult status(EventEnvelope event, String status) {
        return copies.setStatus(ProductEvent.productId(event.data()), status, event.time())
                ? HandleResult.done() : HandleResult.ignored("unknown-product");
    }
}
