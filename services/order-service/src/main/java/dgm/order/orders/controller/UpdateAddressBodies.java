package dgm.order.orders.controller;

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
 * Чтение тела {@code UpdateDeliveryAddressRequest} ({@code updateDeliveryAddress}, OP-72): объект с двумя обязательными полями {@code ticketId} (UUID в
 * нижнем регистре) и {@code email} (адрес не длиннее 254 знаков). Адрес приводится к нормальному виду (без пробелов по краям, в нижнем регистре), как
 * его хранят учётная запись и событие. Не JSON это 400, не тот {@code Content-Type} 415, лишнее поле или неверное значение 422 со списком
 * {@code errors}. Значение адреса в сообщения об ошибках не попадает: это персональные данные.
 */
final class UpdateAddressBodies {

    /** Тело из двух коротких полей: 4 КиБ с запасом. */
    static final long MAX_BODY = 4L * 1024;

    static final int EMAIL_MIN = 3;
    static final int EMAIL_MAX = 254;

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private UpdateAddressBodies() {
    }

    /** Разобранный запрос; {@code email} уже в нормальном виде. */
    record UpdateRequest(UUID ticketId, String email) {

        @Override
        public String toString() {
            return "UpdateRequest[ticketId=" + ticketId + "]";
        }
    }

    static UpdateRequest read(HttpServletRequest request) throws IOException {
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

    static UpdateRequest parse(Map<String, Object> body) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!name.equals("ticketId") && !name.equals("email")) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        UUID ticketId = null;
        Object rawId = body.get("ticketId");
        if (rawId == null) {
            errors.add(error("/ticketId", "required", "Поле обязательно."));
        } else if (!(rawId instanceof String text) || !ID.matcher(text).matches()) {
            errors.add(error("/ticketId", "invalid_format", "Нужен идентификатор UUID в нижнем регистре."));
        } else {
            ticketId = UUID.fromString(text);
        }
        String email = null;
        Object rawEmail = body.get("email");
        if (rawEmail == null) {
            errors.add(error("/email", "required", "Поле обязательно."));
        } else if (!(rawEmail instanceof String text)) {
            errors.add(error("/email", "invalid_format", "Нужна строка с адресом e-mail."));
        } else {
            String normalized = text.strip().toLowerCase(Locale.ROOT);
            if (normalized.length() > EMAIL_MAX) {
                errors.add(error("/email", "too_long", "Адрес не длиннее " + EMAIL_MAX + " знаков."));
            } else if (normalized.length() < EMAIL_MIN || !EMAIL.matcher(normalized).matches()) {
                errors.add(error("/email", "invalid_format", "Нужен адрес e-mail вида имя@домен."));
            } else {
                email = normalized;
            }
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                    Map.of("errors", List.copyOf(errors)), Map.of());
        }
        return new UpdateRequest(ticketId, email);
    }

    private static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }
}
