package dgm.kit.keycloak;

/**
 * Ошибка вызова Admin REST Keycloak. Текст не содержит тела ответа, токенов и адресов электронной почты.
 */
public final class KeycloakException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final boolean retryable;

    /**
     * @param status    код HTTP; 0, если ответа нет (таймаут, обрыв соединения)
     * @param retryable повтор имеет смысл: сбой сети, лимит времени, 5xx и 429; ошибки клиента (4xx) повтором не исправить
     */
    public KeycloakException(int status, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryable = retryable;
    }

    public KeycloakException(int status, String message, boolean retryable) {
        this(status, message, retryable, null);
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    /** Ресурса нет в Keycloak (404). */
    public boolean notFound() {
        return status == 404;
    }
}
