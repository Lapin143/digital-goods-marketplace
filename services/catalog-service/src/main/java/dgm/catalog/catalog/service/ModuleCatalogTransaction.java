package dgm.catalog.catalog.service;

import dgm.catalog.catalog.seller.repository.JdbcSellerProductRepository;
import dgm.kit.boot.Modules;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** {@link CatalogTransaction} на пуле и Outbox модуля {@code catalog} (каркас: {@link Modules}). */
@Component
public final class ModuleCatalogTransaction implements CatalogTransaction {

    private final Modules modules;

    public ModuleCatalogTransaction(Modules modules) {
        this.modules = modules;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return modules.transactions(JdbcSellerProductRepository.MODULE).execute(status -> action.get());
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        modules.outbox(JdbcSellerProductRepository.MODULE).write(topic, aggregateType, aggregateId, eventType, schemaVersion, data);
    }
}
