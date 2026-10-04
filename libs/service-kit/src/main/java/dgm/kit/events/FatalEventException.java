package dgm.kit.events;

/** Исключение обработчика: неисправимая ошибка (нарушена схема, нет обязательного поля). Сообщение уходит в DLQ без повторов. */
public final class FatalEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FatalEventException(String message) {
        super(message);
    }

    public FatalEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
