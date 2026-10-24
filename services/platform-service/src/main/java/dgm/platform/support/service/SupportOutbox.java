package dgm.platform.support.service;

import java.util.Map;

/**
 * Запись событий модуля {@code support} в Outbox текущей транзакции (ADR-005): строка обращения и событие фиксируются вместе. Отдельный
 * интерфейс нужен, чтобы службу проверяли модульными тестами без базы.
 */
public interface SupportOutbox {

    /**
     * @throws IllegalStateException вызов вне транзакции потребителя
     */
    void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data);
}
