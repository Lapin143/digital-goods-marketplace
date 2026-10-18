package dgm.payment.payments.service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция модуля {@code payments}: изменение данных и запись события в Outbox идут в ней одной (ADR-005). Интерфейс нужен, чтобы
 * сервисы платежей проверялись модульными тестами без базы.
 */
public interface PaymentTransaction {

    /** Выполняет действие в одной транзакции. Вызов шлюза внутри действия запрещён (правило 9). */
    <T> T execute(Supplier<T> action);

    /** Выполняет действие после коммита текущей транзакции; без транзакции сразу. */
    void afterCommit(Runnable action);

    /** Записывает событие в Outbox текущей транзакции. */
    void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data);
}
