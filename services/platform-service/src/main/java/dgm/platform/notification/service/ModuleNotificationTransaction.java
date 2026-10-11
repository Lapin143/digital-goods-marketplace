package dgm.platform.notification.service;

import dgm.kit.boot.Modules;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** {@link NotificationTransaction} на пуле и Outbox модуля {@code notification} (каркас: {@link Modules}). */
@Component
public final class ModuleNotificationTransaction implements NotificationTransaction {

    /** Модуль базы {@code notification}: своя роль {@code app_notification}, свой пул и транзакция (application.yml, dgm.db.modules). */
    static final String MODULE = "notification";

    private final Modules modules;

    public ModuleNotificationTransaction(Modules modules) {
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
