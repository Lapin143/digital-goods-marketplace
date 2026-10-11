package dgm.platform.identity.service;

import dgm.kit.boot.Modules;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** {@link IdentityTransaction} на пуле и Outbox модуля {@code identity} (каркас: {@link Modules}). */
@Component
public final class ModuleTransaction implements IdentityTransaction {

    /** Модуль базы {@code identity}: своя роль {@code app_identity}, свой пул и транзакция (application.yml, dgm.db.modules). */
    static final String MODULE = "identity";

    private final Modules modules;

    public ModuleTransaction(Modules modules) {
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
