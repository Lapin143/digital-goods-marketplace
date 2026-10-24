package dgm.platform.support.service;

import dgm.kit.boot.Modules;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@link SupportOutbox} на Outbox модуля {@code support}: запись идёт на соединении транзакции потребителя (каркас: {@link Modules}). */
@Component
public final class ModuleSupportOutbox implements SupportOutbox {

    /** Модуль базы {@code support}: своя роль {@code app_support}, свой пул и транзакция (application.yml, dgm.db.modules). */
    static final String MODULE = "support";

    private final Modules modules;

    public ModuleSupportOutbox(Modules modules) {
        this.modules = modules;
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        modules.outbox(MODULE).write(topic, aggregateType, aggregateId, eventType, schemaVersion, data);
    }
}
