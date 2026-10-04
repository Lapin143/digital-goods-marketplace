package dgm.fixtures;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.HandleResult;
import dgm.kit.events.ProcessedEvents;
import java.util.Set;

/** Нарушение: обработчик события сам пишет в processed_event и обходит обёртку потребителя. */
public final class BadHandler implements EventHandler {

    private final ProcessedEvents processed;

    public BadHandler(ProcessedEvents processed) {
        this.processed = processed;
    }

    @Override
    public String name() {
        return "bad";
    }

    @Override
    public String topic() {
        return "order.events";
    }

    @Override
    public Set<String> types() {
        return Set.of("order.paid");
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        processed.markProcessed(name(), event.id());
        return HandleResult.done();
    }
}
