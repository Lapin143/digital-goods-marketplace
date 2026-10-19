package dgm.order.orders.service;

/**
 * Итог шага саги по событию или сторожу: что сделано с заказом. Обработчик событий переводит его в ответ каркаса.
 *
 * @param kind   применено, проигнорировано или нужно повторить позже
 * @param reason причина для метрики игнорирования и для повтора; у применённого пусто
 */
public record SagaOutcome(Kind kind, String reason) {

    public enum Kind {
        /** Переход выполнен. */
        APPLIED,
        /** Событие неактуально по статусу заказа (повтор, перестановка): ничего не изменено. */
        IGNORED,
        /** Заказ ещё не готов принять событие: обработать позже. */
        RETRY
    }

    public static SagaOutcome applied() {
        return new SagaOutcome(Kind.APPLIED, "");
    }

    public static SagaOutcome ignored(String reason) {
        return new SagaOutcome(Kind.IGNORED, reason);
    }

    public static SagaOutcome retry(String reason) {
        return new SagaOutcome(Kind.RETRY, reason);
    }
}
