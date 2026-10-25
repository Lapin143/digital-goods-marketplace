package dgm.delivery.delivery.events;

import dgm.delivery.delivery.service.DeliveryRepeat;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.HandleResult;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code order.address-updated} (компонент {@code delivery-domain}, SM-06/T1, Р-170): поддержка сменила адрес выданного заказа, в одной
 * транзакции появляется повторная выдача в очереди на новый адрес. Отметку {@code processed_event} делает каркас в той же транзакции. Если у
 * заказа уже есть выдача в очереди либо нет первичной, событие пропускается с причиной ({@code already-queued}, {@code no-primary}); последняя
 * защита от второй выдачи в очереди это уникальный индекс {@code uq_delivery_order_id_queued}. Идентификаторы покупателя и обращения
 * проверяются по схеме, хотя выдаче нужны только заказ и адрес: событие не по схеме это неисправимая ошибка и уходит в DLQ сразу.
 */
@Component
public final class OrderAddressUpdatedHandler implements EventHandler {

    /** Имя потребителя в {@code processed_event}: то же, что у приёма оплаты, это один компонент {@code delivery-domain}. */
    static final String NAME = "delivery-domain";
    static final String TOPIC = "order.events";
    static final String ADDRESS_UPDATED = "order.address-updated";

    private final DeliveryRepeat repeat;

    public OrderAddressUpdatedHandler(DeliveryRepeat repeat) {
        this.repeat = repeat;
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
        return Set.of(ADDRESS_UPDATED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        UUID orderId = EventData.uuid(event, "orderId");
        EventData.uuid(event, "buyerId");
        EventData.uuid(event, "ticketId");
        String address = EventData.emailAddress(event);
        return switch (repeat.accept(orderId, address)) {
            case CREATED -> HandleResult.done();
            case ALREADY_QUEUED -> HandleResult.ignored("already-queued");
            case NO_PRIMARY -> HandleResult.ignored("no-primary");
        };
    }
}
