package dgm.order.orders.service;

import dgm.kit.boot.Modules;
import dgm.order.orders.repository.JdbcOrderStore;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** {@link OrderTransaction} на пуле и Outbox модуля {@code orders} (каркас: {@link Modules}). */
@Component
public final class ModuleOrderTransaction implements OrderTransaction {

    private final Modules modules;

    public ModuleOrderTransaction(Modules modules) {
        this.modules = modules;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return modules.transactions(JdbcOrderStore.MODULE).execute(status -> action.get());
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        modules.outbox(JdbcOrderStore.MODULE).write(topic, aggregateType, aggregateId, eventType, schemaVersion, data);
    }
}
