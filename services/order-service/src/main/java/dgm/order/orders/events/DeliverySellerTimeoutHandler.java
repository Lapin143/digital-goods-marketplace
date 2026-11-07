package dgm.order.orders.events;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.HandleResult;
import dgm.order.orders.service.SagaOrchestrator;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code delivery.seller-timeout} (компонент {@code seller-timeout-handler}, ADR-004, ADR-028): система продавца не ответила на запрос ключа за
 * {@code delivery.seller-response-seconds} или вернула ошибку. Заказ товара по API «оплачен» в «отменён» с причиной {@code seller_api_timeout} (T10),
 * в Outbox уходят {@code order.cancelled} и команда на возврат всей суммы. Повтор события и событие, пришедшее после выдачи, второго эффекта не дают
 * (INV-06, INV-15): повтор отсекает {@code processed_event}, поздний приход статус заказа.
 */
@Component
public final class DeliverySellerTimeoutHandler implements EventHandler {

    static final String NAME = "saga-orchestrator";
    static final String TOPIC = "delivery.events";
    static final String SELLER_TIMEOUT = "delivery.seller-timeout";

    private final SagaOrchestrator saga;

    public DeliverySellerTimeoutHandler(SagaOrchestrator saga) {
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
        return Set.of(SELLER_TIMEOUT);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        return EventData.result(saga.sellerTimeout(EventData.uuid(event, "orderId")));
    }
}
