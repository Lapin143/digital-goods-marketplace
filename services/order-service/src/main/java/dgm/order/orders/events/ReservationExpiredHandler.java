package dgm.order.orders.events;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.HandleResult;
import dgm.order.orders.service.SagaOrchestrator;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code reservation.expired} (компонент {@code saga-orchestrator}, ADR-004, ADR-012): резерв ключей кончился. Заказ «ожидает оплаты» в
 * «отменён» (T6); для других статусов событие относится к уже завершённой попытке и игнорируется. Если оплата придёт позже, это поздняя оплата
 * (ADR-013). Потерянное событие заменяет сторож заказов.
 */
@Component
public final class ReservationExpiredHandler implements EventHandler {

    static final String NAME = "saga-orchestrator";
    static final String TOPIC = "inventory.events";
    static final String EXPIRED = "reservation.expired";

    private final SagaOrchestrator saga;

    public ReservationExpiredHandler(SagaOrchestrator saga) {
        this.saga = saga;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public Set<String> types() {
        return Set.of(EXPIRED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        return EventData.result(saga.reservationExpired(EventData.uuid(event, "orderId")));
    }
}
