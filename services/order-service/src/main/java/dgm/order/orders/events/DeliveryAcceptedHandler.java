package dgm.order.orders.events;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.HandleResult;
import dgm.order.orders.service.SagaOrchestrator;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code delivery.accepted} (компонент {@code saga-orchestrator}, ADR-004, ADR-011): провайдер принял письмо с ключом. «Оплачен» в «выдан»
 * (T9), один раз за жизнь заказа (INV-17). Повторная отправка по обращению даёт новое событие с типом {@code repeat}, но заказ уже «выдан», и оно
 * игнорируется.
 */
@Component
public final class DeliveryAcceptedHandler implements EventHandler {

    static final String NAME = "saga-orchestrator";
    static final String TOPIC = "delivery.events";
    static final String ACCEPTED = "delivery.accepted";

    private final SagaOrchestrator saga;

    public DeliveryAcceptedHandler(SagaOrchestrator saga) {
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
        return Set.of(ACCEPTED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        UUID orderId = EventData.uuid(event, "orderId");
        UUID deliveryId = EventData.uuid(event, "deliveryId");
        Instant acceptedAt = EventData.instant(event, "acceptedAt");
        return EventData.result(saga.deliveryAccepted(orderId, deliveryId, acceptedAt));
    }
}
