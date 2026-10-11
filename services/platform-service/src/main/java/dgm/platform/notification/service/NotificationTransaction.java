package dgm.platform.notification.service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция модуля {@code notification} и запись событий в Outbox в ней (ADR-005): окончательная неудача и событие
 * {@code notification.failed} фиксируются вместе или откатываются вместе. Отдельный интерфейс нужен, чтобы сервисы проверялись модульными
 * тестами без базы.
 */
public interface NotificationTransaction {

    <T> T execute(Supplier<T> action);

    /**
     * Записывает событие в Outbox текущей транзакции.
     *
     * @throws IllegalStateException вызов вне {@link #execute(Supplier)}
     */
    void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data);
}
