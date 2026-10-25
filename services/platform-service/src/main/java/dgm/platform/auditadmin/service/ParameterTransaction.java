package dgm.platform.auditadmin.service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция модуля {@code audit_admin} и запись событий в Outbox в ней (ADR-005): правка параметра, запись аудита и {@code config.changed}
 * фиксируются вместе или откатываются вместе. Исключение внутри действия откатывает транзакцию и летит наружу без изменений.
 * Отдельный интерфейс нужен, чтобы сервис проверялся модульными тестами без базы.
 */
public interface ParameterTransaction {

    <T> T execute(Supplier<T> action);

    /**
     * Записывает событие в Outbox текущей транзакции.
     *
     * @throws IllegalStateException вызов вне {@link #execute(Supplier)}
     */
    void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data);
}
