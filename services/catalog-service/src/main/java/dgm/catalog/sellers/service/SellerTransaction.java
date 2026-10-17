package dgm.catalog.sellers.service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция модуля {@code seller_onboarding}. Если вызов пришёл через ключ идемпотентности, действие присоединяется к транзакции
 * охранника того же модуля, и ответ сохраняется вместе с изменением данных. События пишутся в Outbox этого же модуля, поэтому решение,
 * событие и запись аудита фиксируются вместе или откатываются вместе (ADR-005, INV-43).
 */
public interface SellerTransaction {

    <T> T execute(Supplier<T> action);

    /**
     * Записывает событие в Outbox текущей транзакции.
     *
     * @throws IllegalStateException вызов вне {@link #execute(Supplier)}
     */
    void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data);
}
