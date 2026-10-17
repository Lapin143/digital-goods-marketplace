package dgm.platform.identity.service;

import dgm.kit.boot.Modules;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** {@link IdentityTransaction} на пуле и Outbox модуля {@code identity} (каркас: {@link Modules}). */
@Component
public final class ModuleTransaction implements IdentityTransaction {

    /** Модуль базы {@code identity}: своя роль {@code app_identity}, свой пул и транзакция (application.yml, dgm.db.modules). */
    static final String MODULE = "identity";

    private static final Logger LOG = LoggerFactory.getLogger(ModuleTransaction.class);

    private final Modules modules;

    public ModuleTransaction(Modules modules) {
        this.modules = modules;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return modules.transactions(MODULE).execute(status -> action.get());
    }

    /**
     * Действие регистрируется на фиксацию транзакции, которая сейчас идёт в этом потоке: обработчик события работает внутри транзакции
     * потребителя, а не открывает свою. Исключение действия после фиксации перехватывается: оно не должно превратить зафиксированное
     * событие в повторную доставку, которую отсечёт запись об обработанном (изменение в Keycloak тогда не повторилось бы никогда).
     */
    @Override
    public void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            run(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                run(action);
            }
        });
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // Текст ошибки может нести значения полей, поэтому в журнал идёт только класс
            LOG.warn("Действие после фиксации транзакции не выполнено: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        modules.outbox(MODULE).write(topic, aggregateType, aggregateId, eventType, schemaVersion, data);
    }
}
