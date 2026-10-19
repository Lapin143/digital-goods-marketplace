package dgm.order.orders.service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция модуля {@code orders}: изменение заказа и запись события в Outbox идут в ней одной (ADR-005). Интерфейс отделяет домен заказа
 * от пула соединений, чтобы переходы проверялись модульными тестами без базы. Вызов соседей внутри действия запрещён (правило 9).
 */
public interface OrderTransaction {

    /** Выполняет действие в одной транзакции; исключение откатывает её целиком. */
    <T> T execute(Supplier<T> action);

    /** Записывает событие в Outbox текущей транзакции. */
    void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data);
}
