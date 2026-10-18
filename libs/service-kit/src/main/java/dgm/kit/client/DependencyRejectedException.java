package dgm.kit.client;

import dgm.kit.json.Json;
import java.util.Map;

/**
 * Сосед ответил отказом по смыслу: код 3xx или 4xx (кроме 429, который считается недоступностью). Повторять такой запрос
 * бессмысленно, а для предохранителя это успешный ответ: сосед жив и принял решение. Тело хранится строкой, в текст ошибки оно не
 * попадает.
 */
public final class DependencyRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String peer;
    private final int status;
    private final String body;

    public DependencyRejectedException(String peer, int status, String body) {
        super("Сосед " + peer + " отклонил запрос: " + status + problemSuffix(body));
        this.peer = peer;
        this.status = status;
        this.body = body == null ? "" : body;
    }

    public String peer() {
        return peer;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    /** Код проблемы из тела RFC 9457 (последний сегмент поля {@code type}) или пустая строка, если тела нет или оно не JSON. */
    public String problemCode() {
        return problemCode(body);
    }

    /** Тело ответа как объект JSON или пустой объект. */
    public Map<String, Object> json() {
        try {
            return body.isBlank() ? Map.of() : Json.parseObject(body);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String problemSuffix(String body) {
        String code = problemCode(body);
        return code.isEmpty() ? "" : " (" + code + ")";
    }

    private static String problemCode(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            Object type = Json.parseObject(body).get("type");
            if (type instanceof String s) {
                int slash = s.lastIndexOf('/');
                String code = slash >= 0 ? s.substring(slash + 1) : s;
                return code.matches("[a-z0-9-]{1,64}") ? code : "";
            }
        } catch (RuntimeException e) {
            return "";
        }
        return "";
    }
}
