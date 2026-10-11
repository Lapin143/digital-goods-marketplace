package dgm.platform.identity.otp.service;

/** SMS не отправлено. Текст ошибки не содержит номера и кода. */
public final class SmsSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SmsSendException(String message) {
        super(message);
    }

    public SmsSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
