package dgm.platform.notification.testing;

import dgm.platform.notification.service.NotificationTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Транзакция без базы: события из успешного действия попадают в список, из упавшего пропадают, как при откате. */
public final class FakeNotificationTransaction implements NotificationTransaction {

    /** Записанное событие. */
    public record Event(String topic, String aggregateType, String aggregateId, String type, int schemaVersion, Map<String, Object> data) {
    }

    private final List<Event> committed = new ArrayList<>();
    private List<Event> pending;

    @Override
    public <T> T execute(Supplier<T> action) {
        if (pending != null) {
            throw new IllegalStateException("Транзакция уже открыта");
        }
        pending = new ArrayList<>();
        try {
            T result = action.get();
            committed.addAll(pending);
            return result;
        } finally {
            pending = null;
        }
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        if (pending == null) {
            throw new IllegalStateException("Событие записывается только внутри транзакции");
        }
        pending.add(new Event(topic, aggregateType, aggregateId, eventType, schemaVersion, data));
    }

    public List<Event> events() {
        return List.copyOf(committed);
    }
}
