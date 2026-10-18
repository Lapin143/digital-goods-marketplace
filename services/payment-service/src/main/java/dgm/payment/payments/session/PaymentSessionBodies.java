package dgm.payment.payments.session;

import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.Timestamps;
import dgm.kit.web.RequestBodies;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Чтение тела {@code OpenPaymentSessionRequest} ({@code openPaymentSession}): объект с полями {@code orderId} (UUID в нижнем регистре),
 * {@code amount} ({@code amount} целое число копеек больше нуля, {@code currency} только RUB) и {@code sessionExpiresAt} (RFC 3339 в UTC
 * с миллисекундами). Не JSON это 400, не тот {@code Content-Type} 415, лишнее поле или неверное значение 422 со списком {@code errors}.
 */
public final class PaymentSessionBodies {

    /** Тело из трёх коротких полей: 4 КиБ с запасом. */
    static final long MAX_BODY = 4L * 1024;
    static final long MAX_AMOUNT = 9_007_199_254_740_991L;

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern TIMESTAMP = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$");

    private PaymentSessionBodies() {
    }

    /** Разобранный запрос. */
    public record OpenRequest(UUID orderId, long amount, String currency, Instant sessionExpiresAt) {
    }

    public static OpenRequest read(HttpServletRequest request) throws IOException {
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

    public static OpenRequest parse(Map<String, Object> body) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!name.equals("orderId") && !name.equals("amount") && !name.equals("sessionExpiresAt")) {
                errors.add(error("/" + name, "unknown_value", "Лишнее поле."));
            }
        }
        UUID orderId = orderId(body, errors);
        long amount = 0;
        String currency = null;
        Object money = body.get("amount");
        if (money == null) {
            errors.add(error("/amount", "required", "Поле обязательно."));
        } else if (!(money instanceof Map<?, ?> fields)) {
            errors.add(error("/amount", "invalid_format", "Нужен объект с полями amount и currency."));
        } else {
            for (Object name : fields.keySet()) {
                if (!"amount".equals(name) && !"currency".equals(name)) {
                    errors.add(error("/amount/" + name, "unknown_value", "Лишнее поле."));
                }
            }
            Object value = fields.get("amount");
            if (value == null) {
                errors.add(error("/amount/amount", "required", "Поле обязательно."));
            } else if (!(value instanceof Integer) && !(value instanceof Long)) {
                errors.add(error("/amount/amount", "invalid_format", "Нужно целое число копеек."));
            } else if (((Number) value).longValue() < 1 || ((Number) value).longValue() > MAX_AMOUNT) {
                errors.add(error("/amount/amount", "out_of_range", "Сумма от 1 копейки до " + MAX_AMOUNT + "."));
            } else {
                amount = ((Number) value).longValue();
            }
            Object code = fields.get("currency");
            if (code == null) {
                errors.add(error("/amount/currency", "required", "Поле обязательно."));
            } else if (!"RUB".equals(code)) {
                errors.add(error("/amount/currency", "invalid_format", "В R1 допустима только валюта RUB."));
            } else {
                currency = "RUB";
            }
        }
        Instant expiresAt = null;
        Object raw = body.get("sessionExpiresAt");
        if (raw == null) {
            errors.add(error("/sessionExpiresAt", "required", "Поле обязательно."));
        } else if (!(raw instanceof String text) || !TIMESTAMP.matcher(text).matches() || Timestamps.parse(text).isEmpty()) {
            errors.add(error("/sessionExpiresAt", "invalid_format", "Нужен момент времени RFC 3339 в UTC с миллисекундами."));
        } else {
            expiresAt = Timestamps.parse(text).orElseThrow();
        }
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                    Map.of("errors", List.copyOf(errors)), Map.of());
        }
        return new OpenRequest(orderId, amount, currency, expiresAt);
    }

    private static UUID orderId(Map<String, Object> body, List<Map<String, Object>> errors) {
        Object raw = body.get("orderId");
        if (raw == null) {
            errors.add(error("/orderId", "required", "Поле обязательно."));
        } else if (!(raw instanceof String text) || !ID.matcher(text).matches()) {
            errors.add(error("/orderId", "invalid_format", "Нужен идентификатор UUID в нижнем регистре."));
        } else {
            return UUID.fromString(text);
        }
        return null;
    }

    static Map<String, Object> error(String pointer, String code, String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("pointer", pointer);
        error.put("code", code);
        error.put("detail", detail);
        return error;
    }
}
