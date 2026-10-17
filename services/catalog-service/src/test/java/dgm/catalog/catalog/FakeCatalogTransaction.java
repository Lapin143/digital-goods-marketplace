package dgm.catalog.catalog;

import dgm.catalog.catalog.service.CatalogTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция без базы: выполняет действие сразу, считает вызовы и собирает события Outbox. Как в настоящей транзакции, события,
 * записанные действием, которое закончилось исключением, пропадают (откат), а запись вне действия отклоняется.
 */
public final class FakeCatalogTransaction implements CatalogTransaction {

    /** Событие, записанное в Outbox. */
    public record Published(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
    }

    private final List<Published> committed = new ArrayList<>();
    private final List<Runnable> afterCommitActions = new ArrayList<>();
    private List<Published> pending;
    private List<Runnable> pendingActions;
    private int executions;

    @Override
    public <T> T execute(Supplier<T> action) {
        executions++;
        if (pending != null) {
            return action.get();
        }
        pending = new ArrayList<>();
        pendingActions = new ArrayList<>();
        List<Runnable> actions = pendingActions;
        try {
            T result = action.get();
            committed.addAll(pending);
            afterCommitActions.addAll(actions);
            pending = null;
            pendingActions = null;
            actions.forEach(Runnable::run);
            return result;
        } finally {
            pending = null;
            pendingActions = null;
        }
    }

    /** Как в настоящей транзакции: действие, зарегистрированное в откатившейся транзакции, не выполняется; вне транзакции оно идёт сразу. */
    @Override
    public void afterCommit(Runnable action) {
        if (pendingActions == null) {
            afterCommitActions.add(action);
            action.run();
            return;
        }
        pendingActions.add(action);
    }

    /** Сколько действий «после фиксации» выполнено. */
    public int afterCommitRuns() {
        return afterCommitActions.size();
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
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
