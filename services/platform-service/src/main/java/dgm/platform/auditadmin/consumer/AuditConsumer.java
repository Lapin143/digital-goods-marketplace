package dgm.platform.auditadmin.consumer;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.HandleResult;
import dgm.platform.auditadmin.service.AuditRecordService;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Приёмник журнала аудита (компонент {@code audit-consumer}, Р-164): читает {@code audit.recorded} из {@code audit.events}, куда пишут все
 * сервисы, и передаёт данные {@link AuditRecordService}. Строки пишутся под ролью базы {@code app_audit_writer}: у неё есть только
 * {@code INSERT} и {@code SELECT} журнала и {@code processed_event}.
 */
@Component
public final class AuditConsumer implements EventHandler {

    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler). */
    static final String NAME = "audit-consumer";
    static final String RECORDED = "audit.recorded";

    private final AuditRecordService service;

    public AuditConsumer(AuditRecordService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String module() {
        return "audit-writer";
    }

    @Override
    public String topic() {
        return "audit.events";
    }

    @Override
    public Set<String> types() {
        return Set.of(RECORDED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        return service.record(event.id(), event.data());
    }
}
