package dgm.inventory.inventory.keys.controller;

import dgm.inventory.inventory.keys.domain.KeyBatch;
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
 * Чтение тела {@code SellerKeysRequest} ({@code storeSellerKeys}, OP-99): {@code orderId}, {@code requestId} и от 1 до 10 значений ключей, других полей
 * нет. Значения из тела никуда не пишутся: ни в журнал, ни в сообщения об ошибках. Не тот {@code Content-Type} это 415, тело не JSON 400, нарушение
 * правила 422 со списком {@code errors}, в котором названа только позиция.
 */
public final class SellerKeyBodies {

    /** Десять ключей по 1024 знака и запас на остальное: тело в пределах 64 КБ. */
    static final long MAX_BODY = 64L * 1024;
    /** Больше десяти единиц в заказе не бывает (INV-05). */
    static final int MAX_KEYS = 10;

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** Разобранное тело. */
    public record SellerKeys(UUID orderId, UUID requestId, List<String> keys) {
    }

    private SellerKeyBodies() {
    }

    public static SellerKeys read(HttpServletRequest request) throws IOException {
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
            if (!"orderId".equals(name) && !"requestId".equals(name) && !"keys".equals(name)) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        UUID order = id(body, "orderId", errors);
        UUID requestId = id(body, "requestId", errors);
        List<String> keys = new ArrayList<>();
        if (!(body.get("keys") instanceof List<?> items)) {
            errors.add(error("/keys", body.containsKey("keys") ? "invalid_format" : "required", "Нужен массив строк."));
        } else {
            if (items.isEmpty() || items.size() > MAX_KEYS) {
                errors.add(error("/keys", "out_of_range", "Ключей от 1 до " + MAX_KEYS + "."));
            }
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof String text) {
                    if (text.length() > KeyBatch.MAX_KEY_LENGTH) {
                        errors.add(error("/keys/" + i, "too_long", "Ключ не длиннее " + KeyBatch.MAX_KEY_LENGTH + " знаков."));
                    }
                    keys.add(text);
                } else {
                    errors.add(error("/keys/" + i, "invalid_format", "Ключ это строка."));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.", Map.of("errors", List.copyOf(errors)),
                    Map.of());
        }
        return new SellerKeys(order, requestId, List.copyOf(keys));
    }

    private static UUID id(Map<String, Object> body, String name, List<Map<String, Object>> errors) {
        if (!(body.get(name) instanceof String text)) {
            errors.add(error("/" + name, body.containsKey(name) ? "invalid_format" : "required", "Нужен идентификатор."));
            return null;
        }
        if (!ID.matcher(text).matches()) {
            errors.add(error("/" + name, "invalid_format", "Идентификатор это UUID в нижнем регистре."));
            return null;
        }
        return UUID.fromString(text);
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }
}
