package dgm.platform.identity.testing;

import dgm.platform.identity.service.IdentityTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Транзакция без базы: события из успешного действия попадают в список, из упавшего пропадают, как при откате. */
public final class FakeTransaction implements IdentityTransaction {

    /** Записанное событие. */
    public record Event(String topic, String aggregateType, String aggregateId, String type, int schemaVersion, Map<String, Object> data) {
    }

    private final List<Event> committed = new ArrayList<>();
    private final List<Class<? extends RuntimeException>> failedAfterCommit = new ArrayList<>();
    private List<Event> pending;
    private List<Runnable> afterCommit;

    @Override
    public <T> T execute(Supplier<T> action) {
        if (pending != null) {
            throw new IllegalStateException("Транзакция уже открыта");
        }
        pending = new ArrayList<>();
        afterCommit = new ArrayList<>();
        T result;
        try {
            result = action.get();
            committed.addAll(pending);
        } catch (RuntimeException | Error e) {
            afterCommit = null;
            throw e;
        } finally {
            pending = null;
        }
        List<Runnable> callbacks = afterCommit;
        afterCommit = null;
        callbacks.forEach(this::runAfterCommit);
        return result;
    }

    /** Откладывает действие до фиксации; при откате оно пропадает, вне транзакции выполняется сразу. */
    @Override
    public void afterCommit(Runnable action) {
        if (afterCommit == null) {
            runAfterCommit(action);
        } else {
            afterCommit.add(action);
        }
    }

    /** Как в настоящей транзакции: исключение действия после фиксации наружу не выходит, ошибка проверки (Error) выходит. */
    private void runAfterCommit(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            failedAfterCommit.add(e.getClass());
        }
    }

    /** Классы исключений, которыми закончились действия после фиксации. */
    public List<Class<? extends RuntimeException>> failedAfterCommit() {
        return List.copyOf(failedAfterCommit);
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        if (pending == null) {
            throw new IllegalStateException("Событие записывается только внутри транзакции");
        }
        pending.add(new Event(topic, aggregateType, aggregateId, eventType, schemaVersion, data));
    }

    /** Идёт ли сейчас транзакция: вызовы Keycloak внутри неё запрещены. */
    public boolean active() {
        return pending != null;
    }

    public List<Event> events() {
        return List.copyOf(committed);
    }

    public List<Event> events(String type) {
        return committed.stream().filter(e -> e.type().equals(type)).toList();
    }
}
