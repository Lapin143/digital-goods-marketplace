package dgm.catalog.sellers;

import dgm.catalog.sellers.service.SellerTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция без базы: выполняет действие сразу, считает вызовы и собирает события Outbox. Как в настоящей транзакции, события,
 * записанные действием, которое закончилось исключением, пропадают (откат), а запись вне действия отклоняется.
 */
public final class FakeTransaction implements SellerTransaction {

    /** Событие, записанное в Outbox. */
    public record Published(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
    }

    private final List<Published> committed = new ArrayList<>();
    private List<Published> pending;
    private int executions;
    private RuntimeException publishFailure;

    @Override
    public <T> T execute(Supplier<T> action) {
        executions++;
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

    /** Следующие записи в Outbox падают этим исключением, как при недоступной базе. */
    public void failPublishWith(RuntimeException failure) {
        this.publishFailure = failure;
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        if (publishFailure != null) {
            throw publishFailure;
        }
        if (pending == null) {
            throw new IllegalStateException("Событие записывается только внутри транзакции");
        }
        pending.add(new Published(topic, aggregateType, aggregateId, eventType, schemaVersion, data));
    }

    public int executions() {
        return executions;
    }

    /** События, записанные зафиксированными транзакциями, в порядке записи. */
    public List<Published> published() {
        return List.copyOf(committed);
    }

    public List<Published> published(String eventType) {
        return committed.stream().filter(p -> p.eventType().equals(eventType)).toList();
    }
}
