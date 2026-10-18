package dgm.inventory.inventory.reservation.controller;

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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Чтение тела {@code ReserveRequest} ({@code reserveKeys}, {@code confirmReservation}): объект с тремя обязательными полями
 * {@code orderId}, {@code productId} (UUID в нижнем регистре) и {@code quantity} (целое от 1 до 10). Не JSON это 400, не тот
 * {@code Content-Type} 415, лишнее поле или неверное значение 422 со списком {@code errors}.
 */
final class ReservationBodies {

    /** Тело из трёх коротких полей: 4 КиБ с запасом. */
    static final long MAX_BODY = 4L * 1024;
    static final int MAX_QUANTITY = 10;

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private ReservationBodies() {
    }

    /** Разобранный запрос. */
    record ReserveRequest(UUID orderId, UUID productId, int quantity) {
    }

    static ReserveRequest read(HttpServletRequest request) throws IOException {
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
        return parse(body);
    }

    static ReserveRequest parse(Map<String, Object> body) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!name.equals("orderId") && !name.equals("productId") && !name.equals("quantity")) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        UUID orderId = id(body, "orderId", errors);
        UUID productId = id(body, "productId", errors);
        int quantity = 0;
        Object raw = body.get("quantity");
        if (raw == null) {
            errors.add(error("/quantity", "required", "Поле обязательно."));
        } else if (!(raw instanceof Integer) && !(raw instanceof Long)) {
            errors.add(error("/quantity", "invalid_format", "Нужно целое число."));
        } else {
            long value = ((Number) raw).longValue();
            if (value < 1 || value > MAX_QUANTITY) {
                errors.add(error("/quantity", "out_of_range", "Количество от 1 до " + MAX_QUANTITY + "."));
            } else {
                quantity = (int) value;
            }
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                    Map.of("errors", List.copyOf(errors)), Map.of());
        }
        return new ReserveRequest(orderId, productId, quantity);
    }

    private static UUID id(Map<String, Object> body, String field, List<Map<String, Object>> errors) {
        Object raw = body.get(field);
        String pointer = "/" + field;
        if (raw == null) {
            errors.add(error(pointer, "required", "Поле обязательно."));
        } else if (!(raw instanceof String text) || !ID.matcher(text).matches()) {
            errors.add(error(pointer, "invalid_format", "Нужен идентификатор UUID в нижнем регистре."));
        } else {
            return UUID.fromString(text);
        }
        return null;
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }
}
