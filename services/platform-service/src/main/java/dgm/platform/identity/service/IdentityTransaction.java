package dgm.platform.identity.service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция модуля {@code identity} и запись событий в Outbox в ней (ADR-005): изменение записи и события фиксируются вместе или откатываются
 * вместе. Исключение внутри действия откатывает транзакцию и летит наружу без изменений. Отдельный интерфейс нужен, чтобы сервисы проверялись
 * модульными тестами без базы.
 */
public interface IdentityTransaction {

    <T> T execute(Supplier<T> action);

    /**
     * Записывает событие в Outbox текущей транзакции.
     *
     * @throws IllegalStateException вызов вне {@link #execute(Supplier)}
     */
    void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data);
}
