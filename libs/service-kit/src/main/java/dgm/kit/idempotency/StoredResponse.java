package dgm.kit.idempotency;

import java.util.Map;

/**
 * Ответ первого выполнения запроса, как его хранит и повторяет охранник идемпотентности.
 *
 * @param status  код ответа
 * @param body    тело ответа в JSON; {@code null}, если тела нет
 * @param headers воспроизводимые заголовки ответа (сейчас {@code Location} и {@code ETag}); остальные заголовки повтор строит заново
 */
public record StoredResponse(int status, String body, Map<String, String> headers) {

    public StoredResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null || body.isBlank() ? null : body;
    }

    public StoredResponse(int status, String body) {
        this(status, body, Map.of());
    }

    /** Результат успешный (2xx): только такой ответ запоминается, чтобы повтор после ошибки выполнялся заново. */
    public boolean successful() {
        return status >= 200 && status < 300;
    }
}
