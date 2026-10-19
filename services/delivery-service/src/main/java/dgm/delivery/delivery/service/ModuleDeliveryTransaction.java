package dgm.delivery.delivery.service;

import dgm.delivery.delivery.repository.JdbcDeliveryRepository;
import dgm.kit.boot.Modules;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** {@link DeliveryTransaction} на пуле и Outbox модуля {@code delivery} (каркас: {@link Modules}). */
@Component
public final class ModuleDeliveryTransaction implements DeliveryTransaction {

    private final Modules modules;

    public ModuleDeliveryTransaction(Modules modules) {
        this.modules = modules;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return modules.transactions(JdbcDeliveryRepository.MODULE).execute(status -> action.get());
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        modules.outbox(JdbcDeliveryRepository.MODULE).write(topic, aggregateType, aggregateId, eventType, schemaVersion, data);
    }
}
