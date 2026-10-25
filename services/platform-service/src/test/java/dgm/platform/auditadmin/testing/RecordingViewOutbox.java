package dgm.platform.auditadmin.testing;

import dgm.platform.auditadmin.service.AuditViewOutbox;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Записанные события о просмотре журнала; можно «сломать» запись. */
public final class RecordingViewOutbox implements AuditViewOutbox {

    /** Записанное событие. */
    public record Event(String topic, String aggregateType, String aggregateId, String type, int schemaVersion, Map<String, Object> data) {
    }

    private final List<Event> events = new ArrayList<>();
    private RuntimeException failure;

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        if (failure != null) {
            throw failure;
        }
        events.add(new Event(topic, aggregateType, aggregateId, eventType, schemaVersion, data));
    }

    public List<Event> events() {
        return List.copyOf(events);
    }
}
