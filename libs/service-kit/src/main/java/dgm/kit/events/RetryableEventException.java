package dgm.kit.events;

/**
 * Исключение обработчика: временная ошибка (событие пришло раньше нужного статуса, конфликт блокировки). Любое другое непроверяемое
 * исключение обработчика тоже считается временным, этот класс лишь делает намерение явным.
 */
public final class RetryableEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RetryableEventException(String message) {
        super(message);
    }

    public RetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
