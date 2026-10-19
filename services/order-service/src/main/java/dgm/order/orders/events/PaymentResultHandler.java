package dgm.order.orders.events;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.order.orders.domain.RefundReason;
import dgm.order.orders.service.SagaOrchestrator;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code payment.rejected} и {@code payment.refunded} (компонент {@code saga-orchestrator}, ADR-004): отказ шлюза отменяет заказ «ожидает
 * оплаты» (T5), подтверждённый возврат переводит «отменён» в «возвращён» (T8). Соседей эти ветки не вызывают, поэтому обработчик обычный:
 * переход и отметка {@code processed_event} идут в транзакции каркаса.
 */
@Component
public final class PaymentResultHandler implements EventHandler {

    static final String NAME = "saga-orchestrator";
    static final String TOPIC = "payment.events";
    static final String REJECTED = "payment.rejected";
    static final String REFUNDED = "payment.refunded";

    private final SagaOrchestrator saga;

    public PaymentResultHandler(SagaOrchestrator saga) {
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
        return Set.of(REJECTED, REFUNDED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        UUID orderId = EventData.uuid(event, "orderId");
        return switch (event.type()) {
            case REJECTED -> EventData.result(saga.paymentRejected(orderId));
            case REFUNDED -> EventData.result(saga.refundConfirmed(orderId, EventData.oneOf(event, "refundReason", RefundReason.ALL)));
            default -> throw new FatalEventException("Тип события " + event.type() + " этот обработчик не принимает");
        };
    }
}
