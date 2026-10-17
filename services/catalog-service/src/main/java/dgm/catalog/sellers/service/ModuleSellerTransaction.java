package dgm.catalog.sellers.service;

import dgm.catalog.sellers.repository.JdbcSellerRepository;
import dgm.kit.boot.Modules;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** {@link SellerTransaction} на пуле и Outbox модуля {@code seller_onboarding} (каркас: {@link Modules}). */
@Component
public final class ModuleSellerTransaction implements SellerTransaction {

    private final Modules modules;

    public ModuleSellerTransaction(Modules modules) {
        this.modules = modules;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return modules.transactions(JdbcSellerRepository.MODULE).execute(status -> action.get());
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        modules.outbox(JdbcSellerRepository.MODULE).write(topic, aggregateType, aggregateId, eventType, schemaVersion, data);
    }
}
