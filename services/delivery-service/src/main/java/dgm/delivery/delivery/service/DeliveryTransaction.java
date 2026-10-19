package dgm.delivery.delivery.service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция модуля {@code delivery}: изменение выдачи, запись попытки и событие в Outbox идут в ней одной (ADR-005). Интерфейс нужен, чтобы
 * сервисы выдачи проверялись модульными тестами без базы.
 */
public interface DeliveryTransaction {

    /** Выполняет действие в одной транзакции. Вызов соседа или провайдера внутри действия запрещён (правило 9). */
    <T> T execute(Supplier<T> action);

    /** Записывает событие в Outbox текущей транзакции. */
    void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data);
}
