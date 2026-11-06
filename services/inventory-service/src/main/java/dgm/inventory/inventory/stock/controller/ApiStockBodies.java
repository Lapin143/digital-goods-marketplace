package dgm.inventory.inventory.stock.controller;

import dgm.inventory.inventory.stock.domain.ApiStock;
import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.web.RequestBodies;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Чтение тела {@code ApiStockRequest}: объект с единственным обязательным полем {@code quantity}, целое от 0 до 1 000 000. Не тот
 * {@code Content-Type} это 415, слишком большое тело 413, тело не JSON 400, нарушение правила 422 со списком {@code errors}.
 */
public final class ApiStockBodies {

    /** Тело из одного числа: больше килобайта значит чужое содержимое. */
    static final long MAX_BODY = 1024;

    private ApiStockBodies() {
    }

    public static int quantity(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, "Тип содержимого запроса не поддерживается, нужен application/json.");
        }
        Map<String, Object> body;
        try {
            body = Json.parseObject(new String(RequestBodies.read(request, MAX_BODY), StandardCharsets.UTF_8));
        } catch (JsonException e) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Тело запроса не разбирается как объект JSON.");
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!"quantity".equals(name)) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        int quantity = 0;
        Object value = body.get("quantity");
        if (value == null) {
            errors.add(error("/quantity", body.containsKey("quantity") ? "invalid_format" : "required", "Нужно целое число."));
        } else if (!(value instanceof Integer || value instanceof Long)) {
            errors.add(error("/quantity", "invalid_format", "Нужно целое число."));
        } else if (((Number) value).longValue() < 0 || ((Number) value).longValue() > ApiStock.MAX_QUANTITY) {
            errors.add(error("/quantity", "out_of_range", "Остаток от 0 до " + ApiStock.MAX_QUANTITY + "."));
        } else {
            quantity = ((Number) value).intValue();
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                    Map.of("errors", List.copyOf(errors)), Map.of());
        }
        return quantity;
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }
}
