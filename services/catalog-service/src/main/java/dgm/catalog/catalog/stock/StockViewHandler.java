package dgm.catalog.catalog.stock;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.HandleResult;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Обработчик {@code stock.changed} темы {@code inventory.events} (компонент {@code stock-view-service} в контракте событий, NFT-1.0):
 * обновляет копию остатка для витрины. Работает под ролью модуля {@code catalog}.
 */
@Component
public final class StockViewHandler implements EventHandler {

    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler). */
    static final String NAME = "stock-view-service";

    private final StockViewService service;

    public StockViewHandler(StockViewService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String module() {
        return "catalog";
    }

    @Override
    public String topic() {
        return "inventory.events";
    }

    @Override
    public Set<String> types() {
        return Set.of("stock.changed");
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        return service.apply(StockChange.parse(event.data()), event.time());
    }
}
