package dgm.kit.json;

/** JSON не разбирается или не записывается. Текст не содержит значений из документа: они могут быть персональными. */
public final class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }

    public JsonException(String message) {
        super(message);
    }
}
