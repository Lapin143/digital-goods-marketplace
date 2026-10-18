package dgm.kit.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Запрос к соседу. Повторять его после сбоя безопасно, если запрос идемпотентен: чтение, замена или удаление по адресу либо
 * {@code POST} с {@code Idempotency-Key} (сосед по ключу вернёт прежний результат, ADR-006). Остальные запросы уходят одной попыткой.
 *
 * @param method         метод HTTP
 * @param path           путь с запросом, начинается с {@code /}
 * @param body           тело JSON или {@code null}
 * @param idempotencyKey ключ идемпотентности или {@code null}
 * @param headers        дополнительные заголовки; служебные ({@code Idempotency-Key}, {@code X-Correlation-Id}, {@code traceparent})
 *                       клиент выставляет сам
 */
public record PeerRequest(String method, String path, String body, String idempotencyKey, Map<String, String> headers) {

    public PeerRequest {
        Objects.requireNonNull(method, "method");
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Путь запроса к соседу начинается с «/»");
        }
        headers = Map.copyOf(new LinkedHashMap<>(headers == null ? Map.of() : headers));
    }

    public static PeerRequest get(String path) {
        return new PeerRequest("GET", path, null, null, Map.of());
    }

    public static PeerRequest post(String path, String body, String idempotencyKey) {
        return new PeerRequest("POST", path, body, idempotencyKey, Map.of());
    }

    /** Повтор после сбоя безопасен. */
    public boolean idempotent() {
        return switch (method) {
            case "GET", "HEAD", "PUT", "DELETE", "OPTIONS" -> true;
            default -> idempotencyKey != null;
        };
    }
}
