package dgm.kit.problem;

import dgm.kit.json.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ответ об ошибке по RFC 9457 в формате conventions.md, раздел 9.
 *
 * @param type          тип из реестра
 * @param detail        объяснение случая: без трассировок, SQL, внутренних адресов, ключей и персональных данных
 * @param instance      путь запроса
 * @param correlationId значение X-Correlation-Id
 * @param extensions    расширения типа: {@code errors}, {@code available}, {@code currentStatus} и другие
 */
public record Problem(ProblemType type, String detail, String instance, String correlationId, Map<String, Object> extensions) {

    public Problem {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public String toJson() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type.typeUri());
        body.put("title", type.title());
        body.put("status", type.status());
        body.put("detail", detail);
        if (instance != null) {
            body.put("instance", instance);
        }
        body.put("code", type.code());
        body.put("correlationId", correlationId);
        new java.util.TreeMap<>(extensions).forEach(body::putIfAbsent);
        return Json.write(body);
    }
}
