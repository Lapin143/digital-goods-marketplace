package dgm.platform.support.handler;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.platform.support.service.SupportService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Модель чтения заказа для поддержки по событиям заказа (компонент {@code support-event-handler}, Р-137): {@code order.paid} создаёт
 * строку (покупатель и номер заказа), {@code order.issued} дополняет её временем первичной выдачи. Ключей и адресов в этих полях нет.
 */
@Component
public final class OrderSupportHandler implements EventHandler {

    static final String PAID = "order.paid";
    static final String ISSUED = "order.issued";
    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler), у обеих тем оно одно. */
    static final String NAME = "support-event-handler";

    private final SupportService service;

    public OrderSupportHandler(SupportService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Строки пишутся под ролью базы {@code app_support}. */
    @Override
    public String module() {
        return "support";
    }

    @Override
    public String topic() {
        return "order.events";
    }

    @Override
    public Set<String> types() {
        return Set.of(PAID, ISSUED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        Map<String, Object> data = event.data();
        UUID orderId = EventFields.uuid(data, "orderId");
        long number = EventFields.positive(data, "orderNumber");
        UUID buyerId = EventFields.uuid(data, "buyerId");
        try {
            return switch (event.type()) {
                case PAID -> service.onOrderPaid(orderId, number, buyerId);
                case ISSUED -> service.onOrderIssued(orderId, number, buyerId, EventFields.instant(data, "issuedAt"));
                default -> throw new FatalEventException("Обработчик не принимает события типа " + event.type());
            };
        } catch (DuplicateKeyException e) {
            // Номер заказа уникален (как в order-service): событие с номером чужого заказа противоречит модели чтения, повтор его не исправит
            throw new FatalEventException("Номер заказа " + number + " уже занят другим заказом, событие " + event.type() + " отклонено", e);
        }
    }
}
