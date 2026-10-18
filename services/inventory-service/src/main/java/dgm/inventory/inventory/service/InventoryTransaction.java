package dgm.inventory.inventory.service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Транзакция модуля {@code inventory}. Если вызов пришёл через ключ идемпотентности, действие присоединяется к транзакции охранника
 * того же модуля, и ответ сохраняется вместе с изменением данных. События пишутся в Outbox этого же модуля, поэтому изменение пула и
 * событие о нём фиксируются вместе или откатываются вместе (ADR-005, INV-43).
 */
public interface InventoryTransaction {

    <T> T execute(Supplier<T> action);

    /**
     * Выполняет действие после фиксации текущей транзакции (в том числе внешней, если вызов присоединился к транзакции охранника
     * идемпотентности); вне транзакции выполняет сразу. Для побочных действий, которые не должны опережать фиксацию: постановка
     * таймера в Redis. Исключение действия наружу не выходит за пределы вызывающего: действие должно обработать его само.
     */
    void afterCommit(Runnable action);

    /**
     * Записывает событие в Outbox текущей транзакции.
     *
     * @throws IllegalStateException вызов вне {@link #execute(Supplier)}
     */
    void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data);
}
