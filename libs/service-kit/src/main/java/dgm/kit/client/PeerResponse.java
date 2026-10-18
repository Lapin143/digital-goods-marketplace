package dgm.kit.client;

import dgm.kit.json.Json;
import java.util.Map;

/**
 * Успешный ответ соседа (код 2xx). Тело хранится строкой: значения секретов в ответах (значения ключей) читает только вызывающий,
 * клиент их не журналирует.
 *
 * @param status  код ответа
 * @param body    тело или пустая строка
 * @param headers заголовки ответа, имена в нижнем регистре
 */
public record PeerResponse(int status, String body, Map<String, String> headers) {

    public PeerResponse {
        body = body == null ? "" : body;
        headers = Map.copyOf(headers);
    }

    /** Тело как объект JSON. */
    public Map<String, Object> json() {
        return Json.parseObject(body);
    }

    /** Значение заголовка ответа или {@code null}. */
    public String header(String name) {
        return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }
}
