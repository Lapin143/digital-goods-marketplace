package dgm.kit.events;

/**
 * Итог обработки события обработчиком.
 *
 * @param applied изменение применено; {@code false}: событие неактуально и проигнорировано по правилу (например, E5)
 * @param reason  причина игнорирования для метрики {@code events_ignored_total{reason}}; у применённого пусто
 */
public record HandleResult(boolean applied, String reason) {

    /** Изменение применено. */
    public static HandleResult done() {
        return new HandleResult(true, "");
    }

    /** Событие уже неактуально по статусу или не касается обработчика; смещение подтверждается, в метрике причина. */
    public static HandleResult ignored(String reason) {
        return new HandleResult(false, reason);
    }
}
