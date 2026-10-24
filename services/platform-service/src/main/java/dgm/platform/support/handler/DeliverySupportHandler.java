package dgm.platform.support.handler;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.platform.support.service.SupportService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Обращения системы по событиям выдачи (компонент {@code support-event-handler}, Р-137, SM-07): {@code delivery.accepted} обновляет статус
 * выдачи, {@code delivery.failed} и {@code delivery.overdue} создают обращение (не больше одного открытого на заказ), {@code delivery.delivered}
 * закрывает открытое обращение. Заказ называется идентификатором; значений ключей в этих событиях нет.
 */
@Component
public final class DeliverySupportHandler implements EventHandler {

    static final String ACCEPTED = "delivery.accepted";
    static final String DELIVERED = "delivery.delivered";
    static final String FAILED = "delivery.failed";
    static final String OVERDUE = "delivery.overdue";
    static final String NAME = "support-event-handler";

    private final SupportService service;

    public DeliverySupportHandler(SupportService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String module() {
        return "support";
    }

    @Override
    public String topic() {
        return "delivery.events";
    }

    @Override
    public Set<String> types() {
        return Set.of(ACCEPTED, DELIVERED, FAILED, OVERDUE);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        Map<String, Object> data = event.data();
        UUID orderId = EventFields.uuid(data, "orderId");
        return switch (event.type()) {
            case ACCEPTED -> service.onDeliveryAccepted(orderId, event.time());
            case DELIVERED -> service.onDeliveryDelivered(orderId, event.time());
            case FAILED -> service.onDeliveryFailed(orderId, EventFields.text(data, "reason", 64), event.time());
            case OVERDUE -> service.onDeliveryOverdue(orderId, event.time());
            default -> throw new FatalEventException("Обработчик не принимает события типа " + event.type());
        };
    }
}
