package dgm.platform.identity.otp.service;

/** Письмо с кодом не отправлено. Текст ошибки не содержит адреса и кода. */
public final class EmailCodeSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailCodeSendException(String message) {
        super(message);
    }

    public EmailCodeSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
