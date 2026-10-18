package dgm.payment.payments.service;

import dgm.kit.boot.Modules;
import dgm.payment.payments.repository.JdbcPaymentRepository;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** {@link PaymentTransaction} на пуле и Outbox модуля {@code payments} (каркас: {@link Modules}). */
@Component
public final class ModulePaymentTransaction implements PaymentTransaction {

    private final Modules modules;

    public ModulePaymentTransaction(Modules modules) {
        this.modules = modules;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return modules.transactions(JdbcPaymentRepository.MODULE).execute(status -> action.get());
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
        modules.outbox(JdbcPaymentRepository.MODULE).write(topic, aggregateType, aggregateId, eventType, schemaVersion, data);
    }
}
