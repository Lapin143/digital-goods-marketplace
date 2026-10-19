package dgm.delivery.delivery.events;

import dgm.delivery.delivery.service.DeliveryIntake;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code order.paid} (компонент {@code delivery-domain}, SM-06/T1, INV-18): заказ оплачен, в одной транзакции появляется первичная выдача «в
 * очереди» (снимок адреса, название товара и число ключей из события) и открывается контроль доставки. Отметку {@code processed_event} делает
 * каркас в той же транзакции. Повтор события, в том числе под другим идентификатором, второй выдачи не создаёт: первичная выдача заказа одна.
 */
@Component
public final class OrderPaidHandler implements EventHandler {

    /** Имя потребителя в {@code processed_event} и в контракте событий (x-consumer.handler). */
    static final String NAME = "delivery-domain";
    static final String TOPIC = "order.events";
    static final String PAID = "order.paid";

    private final DeliveryIntake intake;

    public OrderPaidHandler(DeliveryIntake intake) {
        this.intake = intake;
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
        return Set.of(PAID);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        UUID orderId = EventData.uuid(event, "orderId");
        UUID buyerId = EventData.uuid(event, "buyerId");
        String title = EventData.text(event, "productTitle", 1, 200);
        int quantity = EventData.integer(event, "quantity", 1, 10);
        Instant paidAt = EventData.instant(event, "paidAt");
        if (!(event.data().get("delivery") instanceof Map<?, ?> delivery) || !"email".equals(delivery.get("channel"))
                || !(delivery.get("address") instanceof String address) || address.length() < 3 || address.length() > 254) {
            throw new FatalEventException("В событии " + event.id() + " нет допустимого адреса доставки по почте");
        }
        return intake.accept(orderId, buyerId, address, title, quantity, paidAt) ? HandleResult.done() : HandleResult.ignored("delivery-exists");
    }
}
