package dgm.kit.events;

import java.util.UUID;

/** Таблица обработанных событий потребителя (ADR-006, уровень 2 идемпотентности). */
public interface ProcessedEvents {

    /**
     * Записывает пару (потребитель, событие) в текущей транзакции.
     *
     * @return {@code true}, если строка вставлена (событие новое); {@code false}: событие уже обрабатывалось, это дубль
     */
    boolean markProcessed(String consumer, UUID eventId);

    /**
     * Читает без записи, обработано ли событие. Нужно обработчикам с внешними шагами ({@link ExternalStepsHandler}): дубль отсеивается до
     * вызовов соседей. Ответ {@code false} не гарантия: параллельный потребитель мог успеть позже, окончательно дубль определяет
     * {@link #markProcessed} внутри транзакции.
     */
    default boolean isProcessed(String consumer, UUID eventId) {
        return false;
    }
}
