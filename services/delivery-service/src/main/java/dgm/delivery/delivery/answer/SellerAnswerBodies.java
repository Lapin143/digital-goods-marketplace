package dgm.delivery.delivery.answer;

import dgm.delivery.delivery.service.SellerAnswerService.Answer;
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
import java.util.regex.Pattern;

/**
 * Чтение тела {@code SellerAnswerRequest} ({@code respondSellerDelivery}, OP-101): либо {@code keys} (от 1 до 10 строк до 1024 знаков), либо {@code error}
 * ({@code code} и необязательное {@code message} до 500 знаков), другого нет. Не тот {@code Content-Type} это 415, тело не JSON 400, нарушение схемы,
 * в том числе лишнее поле, 422 со списком {@code errors}. Значения ключей и текст отказа в сообщения об ошибках не попадают: названа только позиция.
 */
public final class SellerAnswerBodies {

    /** Десять ключей по 1024 знака и запас на остальное: тело в пределах 64 КБ. */
    static final long MAX_BODY = 64L * 1024;
    static final int MAX_KEYS = 10;
    static final int MAX_KEY_LENGTH = 1024;
    static final int MAX_MESSAGE = 500;

    private static final Pattern CODE = Pattern.compile("^[a-z0-9_]{1,64}$");

    private SellerAnswerBodies() {
    }

    public static Answer read(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, "Тип содержимого запроса не поддерживается, нужен application/json.");
        }
        try {
            return parse(Json.parseObject(new String(RequestBodies.read(request, MAX_BODY), StandardCharsets.UTF_8)));
        } catch (JsonException e) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Тело запроса не разбирается как объект JSON.");
        }
    }

    /** Разбор объекта: ровно одно из {@code keys} и {@code error}. */
    public static Answer parse(Map<String, Object> body) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!"keys".equals(name) && !"error".equals(name)) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        boolean hasKeys = body.containsKey("keys");
        boolean hasError = body.containsKey("error");
        Answer answer = null;
        if (hasKeys == hasError) {
            errors.add(error(hasKeys ? "/error" : "/keys", hasKeys ? "unknown_value" : "required", "Нужно одно из полей: keys или error."));
        } else if (hasKeys) {
            answer = keys(body.get("keys"), errors);
        } else {
            answer = refusal(body.get("error"), errors);
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.", Map.of("errors", List.copyOf(errors)),
                    Map.of());
        }
        return answer;
    }

    private static Answer keys(Object value, List<Map<String, Object>> errors) {
        if (!(value instanceof List<?> items)) {
            errors.add(error("/keys", "invalid_format", "Нужен массив строк."));
            return null;
        }
        if (items.isEmpty() || items.size() > MAX_KEYS) {
            errors.add(error("/keys", "out_of_range", "Ключей от 1 до " + MAX_KEYS + "."));
        }
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof String text)) {
                errors.add(error("/keys/" + i, "invalid_format", "Ключ это строка."));
            } else if (text.isBlank()) {
                errors.add(error("/keys/" + i, "too_short", "Ключ не пуст."));
            } else if (text.length() > MAX_KEY_LENGTH) {
                errors.add(error("/keys/" + i, "too_long", "Ключ не длиннее " + MAX_KEY_LENGTH + " знаков."));
            } else {
                keys.add(text);
            }
        }
        return new Answer.Keys(List.copyOf(keys));
    }

    private static Answer refusal(Object value, List<Map<String, Object>> errors) {
        if (!(value instanceof Map<?, ?> error)) {
            errors.add(error("/error", "invalid_format", "Нужен объект с полем code."));
            return null;
        }
        for (Object name : error.keySet()) {
            if (!"code".equals(name) && !"message".equals(name)) {
                errors.add(error("/error/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        String code = null;
        if (!(error.get("code") instanceof String text)) {
            errors.add(error("/error/code", error.containsKey("code") ? "invalid_format" : "required", "Нужен код отказа."));
        } else if (!CODE.matcher(text).matches()) {
            errors.add(error("/error/code", "invalid_format", "Код отказа: строчные латинские буквы, цифры и подчёркивание."));
        } else {
            code = text;
        }
        String message = null;
        if (error.containsKey("message")) {
            if (!(error.get("message") instanceof String text)) {
                errors.add(error("/error/message", "invalid_format", "Пояснение это строка."));
            } else if (text.length() > MAX_MESSAGE) {
                errors.add(error("/error/message", "too_long", "Пояснение не длиннее " + MAX_MESSAGE + " знаков."));
            } else if (!text.isBlank()) {
                message = text.strip();
            }
        }
        return code == null ? null : new Answer.Refusal(code, message);
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }
}
