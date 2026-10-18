package dgm.payment.payments.manual;

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
 * Чтение необязательного тела {@code CompleteManualRefundRequest}: объект с необязательным полем {@code externalRefundId} (строка от 1 до
 * 128 знаков). Тела может не быть вовсе. Не JSON это 400, не тот {@code Content-Type} 415, лишнее поле или неверное значение 422 со
 * списком {@code errors}.
 */
final class ManualRefundBodies {

    /** Один необязательный идентификатор до 128 знаков: 4 КиБ с запасом. */
    static final long MAX_BODY = 4L * 1024;
    static final int MAX_ID = 128;

    private ManualRefundBodies() {
    }

    /** @return идентификатор возврата в кабинете шлюза или {@code null}, если его не передали */
    static String externalRefundId(HttpServletRequest request) throws IOException {
        byte[] raw = RequestBodies.read(request, MAX_BODY);
        if (raw.length == 0) {
            return null;
        }
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, "Тип содержимого запроса не поддерживается, нужен application/json.");
        }
        Map<String, Object> body;
        try {
            body = Json.parseObject(new String(raw, StandardCharsets.UTF_8));
        } catch (JsonException e) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Тело запроса не разбирается как объект JSON.");
        }
        return parse(body);
    }

    static String parse(Map<String, Object> body) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!name.equals("externalRefundId")) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        String id = null;
        Object raw = body.get("externalRefundId");
        if (raw != null) {
            if (!(raw instanceof String text)) {
                errors.add(error("/externalRefundId", "invalid_format", "Нужна строка."));
            } else if (text.isBlank()) {
                errors.add(error("/externalRefundId", "too_short", "Идентификатор не может быть пустым."));
            } else if (text.length() > MAX_ID) {
                errors.add(error("/externalRefundId", "too_long", "Идентификатор не длиннее " + MAX_ID + " знаков."));
            } else {
                id = text;
            }
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                    Map.of("errors", List.copyOf(errors)), Map.of());
        }
        return id;
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }
}
