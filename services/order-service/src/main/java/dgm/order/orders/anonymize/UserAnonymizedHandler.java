package dgm.order.orders.anonymize;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.order.orders.service.OrderTransitions;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code user.anonymized} (компонент {@code order-domain}, INV-44): пользователь обезличен, снимок его адреса доставки во всех его заказах
 * заменяется. Заказы остаются (суммы, статусы и история нужны учёту), уходят только персональные данные. Повторное событие безвредно: заказы с
 * уже заменённым адресом не затрагиваются.
 */
@Component
public final class UserAnonymizedHandler implements EventHandler {

    /** Имя потребителя в {@code processed_event} и в контракте событий (x-consumer.handler). */
    static final String NAME = "order-domain";
    static final String TOPIC = "identity.events";
    static final String ANONYMIZED = "user.anonymized";

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final OrderTransitions transitions;

    public UserAnonymizedHandler(OrderTransitions transitions) {
        this.transitions = transitions;
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
        if (!(event.data().get("userId") instanceof String userId) || !ID.matcher(userId).matches()) {
            throw new FatalEventException("В событии " + event.id() + " поле userId не идентификатор");
        }
        int changed = transitions.anonymize(UUID.fromString(userId));
        return changed > 0 ? HandleResult.done() : HandleResult.ignored("no-orders-to-anonymize");
    }
}
