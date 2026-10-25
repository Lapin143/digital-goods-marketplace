package dgm.platform.auditadmin.testing;

import dgm.platform.auditadmin.service.ParameterTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция без базы: выполняет действие сразу и собирает события Outbox. Как в настоящей транзакции, события действия, которое закончилось
 * исключением, пропадают (откат), а запись вне действия отклоняется.
 */
public final class FakeParameterTransaction implements ParameterTransaction {

    /** Событие, записанное в Outbox. */
    public record Published(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
    }

    private final List<Published> committed = new ArrayList<>();
    private List<Published> pending;
    private RuntimeException publishFailure;

    public void failPublishWith(RuntimeException failure) {
        this.publishFailure = failure;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        if (pending != null) {
            return action.get();
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
        if (publishFailure != null) {
            throw publishFailure;
        }
        pending.add(new Published(topic, aggregateType, aggregateId, eventType, schemaVersion, data));
    }

    public List<Published> published() {
        return List.copyOf(committed);
    }

    public List<Published> published(String eventType) {
        return committed.stream().filter(p -> p.eventType().equals(eventType)).toList();
    }
}
