package dgm.platform.auditadmin.service;

import dgm.kit.boot.Modules;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@link AuditViewOutbox} на транзакции и Outbox модуля {@code audit_admin}: событие фиксируется до возврата из метода. */
@Component
public final class ModuleAuditViewOutbox implements AuditViewOutbox {

    /** Модуль базы {@code audit-admin}: своя роль {@code app_audit_admin}, свой пул (application.yml, dgm.db.modules). */
    static final String MODULE = "audit-admin";

    private final Modules modules;

    public ModuleAuditViewOutbox(Modules modules) {
        this.modules = modules;
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        modules.transactions(MODULE).execute(status -> {
            modules.outbox(MODULE).write(topic, aggregateType, aggregateId, eventType, schemaVersion, data);
            return null;
        });
    }
}
