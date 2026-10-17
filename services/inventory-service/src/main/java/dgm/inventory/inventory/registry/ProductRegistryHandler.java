package dgm.inventory.inventory.registry;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.HandleResult;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Обработчик событий товара темы {@code catalog.events} (компонент {@code product-registry} в контракте событий). Работает под ролью
 * модуля {@code inventory}.
 */
@Component
public final class ProductRegistryHandler implements EventHandler {

    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler). */
    static final String NAME = "product-registry";

    private final ProductRegistryService service;

    public ProductRegistryHandler(ProductRegistryService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String module() {
        return JdbcProductCopyRepository.MODULE;
    }

    @Override
    public String topic() {
        return "catalog.events";
    }

    @Override
    public Set<String> types() {
        return Set.of("product.created", "product.updated", "product.published", "product.rejected", "product.blocked");
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        return service.apply(event);
    }
}
