package dgm.delivery.delivery;

import dgm.delivery.delivery.service.DeliveryTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Транзакция без базы для модульных тестов: выполняет действие сразу и запоминает события, записанные в Outbox. */
public final class FakeTransaction implements DeliveryTransaction {

    /** Событие, записанное в Outbox. */
    public record Published(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
    }

    private final List<Published> published = new ArrayList<>();

    @Override
    public <T> T execute(Supplier<T> action) {
        return action.get();
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        published.add(new Published(topic, aggregateType, aggregateId, eventType, schemaVersion, data));
    }

    public List<Published> published() {
        return published;
    }

    public List<Published> published(String eventType) {
        return published.stream().filter(p -> p.eventType().equals(eventType)).toList();
    }
}
