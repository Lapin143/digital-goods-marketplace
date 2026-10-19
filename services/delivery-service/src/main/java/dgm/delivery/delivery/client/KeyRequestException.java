package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.AttemptResult;
import java.util.Objects;

/**
 * Ключи заказа не получены. Попытка отправки неудачна, повтор по расписанию. Код короткий идентификатор по маске таблицы попыток,
 * текст сообщения без значений и адресов.
 */
public final class KeyRequestException extends RuntimeException {

    private final String code;

    public KeyRequestException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        if (!AttemptResult.CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Код ошибки должен подходить под маску " + AttemptResult.CODE.pattern());
        }
    }

    /** Код ошибки для записи попытки. */
    public String code() {
        return code;
    }
}
