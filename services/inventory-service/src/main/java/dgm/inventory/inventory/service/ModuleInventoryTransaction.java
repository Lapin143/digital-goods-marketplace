package dgm.inventory.inventory.service;

import dgm.inventory.inventory.registry.JdbcProductCopyRepository;
import dgm.kit.boot.Modules;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** {@link InventoryTransaction} на пуле и Outbox модуля {@code inventory} (каркас: {@link Modules}). */
@Component
public final class ModuleInventoryTransaction implements InventoryTransaction {

    private final Modules modules;

    public ModuleInventoryTransaction(Modules modules) {
        this.modules = modules;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return modules.transactions(JdbcProductCopyRepository.MODULE).execute(status -> action.get());
    }

    @Override
    public void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        modules.outbox(JdbcProductCopyRepository.MODULE).write(topic, aggregateType, aggregateId, eventType, schemaVersion, data);
    }
}
