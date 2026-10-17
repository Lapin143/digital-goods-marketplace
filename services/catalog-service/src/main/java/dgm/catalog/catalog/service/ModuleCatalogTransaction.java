package dgm.catalog.catalog.service;

import dgm.catalog.catalog.seller.repository.JdbcSellerProductRepository;
import dgm.kit.boot.Modules;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** {@link CatalogTransaction} на пуле и Outbox модуля {@code catalog} (каркас: {@link Modules}). */
@Component
public final class ModuleCatalogTransaction implements CatalogTransaction {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleCatalogTransaction.class);

    private final Modules modules;

    public ModuleCatalogTransaction(Modules modules) {
        this.modules = modules;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return modules.transactions(JdbcSellerProductRepository.MODULE).execute(status -> action.get());
    }

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
        modules.outbox(JdbcSellerProductRepository.MODULE).write(topic, aggregateType, aggregateId, eventType, schemaVersion, data);
    }
}
