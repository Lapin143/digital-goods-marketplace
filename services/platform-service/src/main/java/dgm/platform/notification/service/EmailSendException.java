package dgm.platform.notification.service;

import java.util.regex.Pattern;

/**
 * Письмо провайдер не принял. Текст ошибки и код не содержат адреса, темы и текста письма.
 */
public final class EmailSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final Pattern CODE = Pattern.compile("^[a-z0-9_.-]{1,64}$");

    private final String code;
    private final boolean permanent;

    /**
     * @param code      код ошибки для столбца {@code last_error_code}: строчные буквы, цифры, «_», «.», «-»
     * @param permanent {@code true}: провайдер отверг письмо (адрес или запрос), повтор не поможет; {@code false}: временный сбой
     */
    public EmailSendException(String code, boolean permanent, String message) {
        this(code, permanent, message, null);
    }

    public EmailSendException(String code, boolean permanent, String message, Throwable cause) {
        super(message, cause);
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Код ошибки не подходит столбцу last_error_code");
        }
        this.code = code;
        this.permanent = permanent;
    }

    public String code() {
        return code;
    }

    public boolean permanent() {
        return permanent;
    }
}
