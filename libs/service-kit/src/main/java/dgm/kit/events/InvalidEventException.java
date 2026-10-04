package dgm.kit.events;

/** Сообщение не является корректным конвертом события: в DLQ сразу, повторы не помогут (conventions.md, 12.2). */
public final class InvalidEventException extends Exception {

    private static final long serialVersionUID = 1L;

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
