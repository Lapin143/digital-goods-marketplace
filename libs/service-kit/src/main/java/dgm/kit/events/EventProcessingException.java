package dgm.kit.events;

/**
 * Запись не доведена до конца: тема недоставленного недоступна или поток прерван. Смещение подтверждать нельзя, запись будет
 * прочитана снова (поэтому дубль возможен, а потеря нет).
 */
public final class EventProcessingException extends Exception {

    private static final long serialVersionUID = 1L;

    public EventProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
