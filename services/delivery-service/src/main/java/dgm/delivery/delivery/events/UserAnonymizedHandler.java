package dgm.delivery.delivery.events;

import dgm.delivery.delivery.domain.DeliveryAddress;
import dgm.delivery.delivery.repository.DeliveryRepository;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.HandleResult;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code user.anonymized} (компонент {@code delivery-domain}, INV-44): пользователь обезличен, адрес в его открытых выдачах («в очереди» и
 * «отправлено») заменяется на зарезервированный адрес, письмо на который не уйдёт. Закрытые выдачи («доставлено», «ошибка») не меняются:
 * письмо в них уже ушло или не уйдёт никогда. Повторное событие безвредно: уже заменённые адреса не затрагиваются.
 */
@Component
public final class UserAnonymizedHandler implements EventHandler {

    /** Имя потребителя в {@code processed_event} и в контракте событий (x-consumer.handler). */
    static final String NAME = "delivery-domain";
    static final String TOPIC = "identity.events";
    static final String ANONYMIZED = "user.anonymized";

    private final DeliveryRepository deliveries;

    public UserAnonymizedHandler(DeliveryRepository deliveries) {
        this.deliveries = deliveries;
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
        return Set.of(ANONYMIZED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        UUID userId = EventData.uuid(event, "userId");
        int changed = deliveries.anonymizeOpen(userId, DeliveryAddress.ANONYMIZED);
        return changed > 0 ? HandleResult.done() : HandleResult.ignored("no-open-deliveries");
    }
}
