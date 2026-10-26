package dgm.platform.notification.api;

/** Письмо напрямую не отправлено: провайдер не принял его или не ответил вовремя. Сообщение не содержит адреса, текста и параметров. */
public final class DirectMailException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DirectMailException(String message, Throwable cause) {
        super(message, cause);
    }
}
