package dgm.order.orders.controller;

import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.web.RequestBodies;
import dgm.order.orders.domain.OrderPricing;
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
 * Чтение тела {@code CreateOrderRequest} ({@code createOrder}): объект с двумя обязательными полями {@code productId} (UUID в нижнем регистре) и
 * {@code quantity} (целое от 1 до 10, INV-10). Не JSON это 400, не тот {@code Content-Type} 415, лишнее поле или неверное значение 422 со
 * списком {@code errors}.
 */
final class CreateOrderBodies {

    /** Тело из двух коротких полей: 4 КиБ с запасом. */
    static final long MAX_BODY = 4L * 1024;

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private CreateOrderBodies() {
    }

    /** Разобранный запрос. */
    record CreateRequest(UUID productId, int quantity) {
    }

    static CreateRequest read(HttpServletRequest request) throws IOException {
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

    static CreateRequest parse(Map<String, Object> body) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!name.equals("productId") && !name.equals("quantity")) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        UUID productId = null;
        Object rawId = body.get("productId");
        if (rawId == null) {
            errors.add(error("/productId", "required", "Поле обязательно."));
        } else if (!(rawId instanceof String text) || !ID.matcher(text).matches()) {
            errors.add(error("/productId", "invalid_format", "Нужен идентификатор UUID в нижнем регистре."));
        } else {
            productId = UUID.fromString(text);
        }
        int quantity = 0;
        Object rawQuantity = body.get("quantity");
        if (rawQuantity == null) {
            errors.add(error("/quantity", "required", "Поле обязательно."));
        } else if (!(rawQuantity instanceof Integer) && !(rawQuantity instanceof Long)) {
            errors.add(error("/quantity", "invalid_format", "Нужно целое число."));
        } else {
            long value = ((Number) rawQuantity).longValue();
            if (value < OrderPricing.MIN_QUANTITY || value > OrderPricing.MAX_QUANTITY) {
                errors.add(error("/quantity", "out_of_range", "Количество от " + OrderPricing.MIN_QUANTITY + " до " + OrderPricing.MAX_QUANTITY + "."));
            } else {
                quantity = (int) value;
            }
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                    Map.of("errors", List.copyOf(errors)), Map.of());
        }
        return new CreateRequest(productId, quantity);
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }
}
