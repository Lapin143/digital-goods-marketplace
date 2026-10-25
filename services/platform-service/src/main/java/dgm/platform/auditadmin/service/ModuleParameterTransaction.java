package dgm.platform.auditadmin.service;

import dgm.kit.boot.Modules;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** {@link ParameterTransaction} на пуле и Outbox модуля {@code audit-admin} (каркас: {@link Modules}). */
@Component
public final class ModuleParameterTransaction implements ParameterTransaction {

    /** Модуль базы {@code audit-admin}: своя роль {@code app_audit_admin}, свой пул (application.yml, dgm.db.modules). */
    static final String MODULE = "audit-admin";

    private final Modules modules;

    public ModuleParameterTransaction(Modules modules) {
        this.modules = modules;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return modules.transactions(MODULE).execute(status -> action.get());
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        modules.outbox(MODULE).write(topic, aggregateType, aggregateId, eventType, schemaVersion, data);
    }
}
