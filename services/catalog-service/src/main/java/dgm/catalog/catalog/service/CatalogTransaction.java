package dgm.catalog.catalog.service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция модуля {@code catalog}. Если вызов пришёл через ключ идемпотентности, действие присоединяется к транзакции охранника
 * того же модуля, и ответ сохраняется вместе с изменением данных. События пишутся в Outbox этого же модуля, поэтому изменение товара
 * и событие о нём фиксируются вместе или откатываются вместе (ADR-005, INV-43).
 */
public interface CatalogTransaction {

    <T> T execute(Supplier<T> action);

    /**
     * Записывает событие в Outbox текущей транзакции.
     *
     * @throws IllegalStateException вызов вне {@link #execute(Supplier)}
     */
    void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data);
}
