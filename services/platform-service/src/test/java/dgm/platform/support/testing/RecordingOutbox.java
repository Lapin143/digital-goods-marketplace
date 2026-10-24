package dgm.platform.support.testing;

import dgm.platform.support.service.SupportOutbox;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Записанные события модуля поддержки. */
public final class RecordingOutbox implements SupportOutbox {

    /** Записанное событие. */
    public record Event(String topic, String aggregateType, String aggregateId, String type, int schemaVersion, Map<String, Object> data) {
    }

    private final List<Event> events = new ArrayList<>();

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        events.add(new Event(topic, aggregateType, aggregateId, eventType, schemaVersion, data));
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public List<Event> events(String type) {
        return events.stream().filter(e -> e.type().equals(type)).toList();
    }
}
