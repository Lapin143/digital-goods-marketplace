package dgm.payment.payments.webhook;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Чтение тела уведомления шлюза ({@code receivePaymentGatewayNotification}, схема {@code PaymentGatewayNotification}). Не JSON и
 * нарушение схемы это 400 (других кодов у операции для разбора нет), не тот {@code Content-Type} 415. Тело читается после проверки
 * подписи, поэтому сюда попадает только то, что подписал шлюз. Значения в сообщения об ошибках не попадают.
 */
public final class GatewayNotificationBodies {

    /** Предел тела 64 КБ, как в описании операции и на шлюзе платформы. */
    public static final long MAX_BODY = 64L * 1024;

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Set<String> TOP = Set.of("eventId", "type", "occurredAt", "data");
    private static final Set<String> DATA = Set.of("paymentId", "orderId", "amount", "refundId", "failureReason");
    private static final Set<String> REASONS = Set.of("declined", "insufficient_funds", "authentication_failed", "other");
    private static final long MAX_AMOUNT = 9_007_199_254_740_991L;

    private GatewayNotificationBodies() {
    }

    public static GatewayNotification read(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, "Тип содержимого запроса не поддерживается, нужен application/json.");
        }
        try {
            return parse(Json.parseObject(new String(RequestBodies.read(request, MAX_BODY), StandardCharsets.UTF_8)));
        } catch (JsonException e) {
            throw bad("Тело уведомления не разбирается как объект JSON.");
        }
    }

    public static GatewayNotification parse(Map<String, Object> body) {
        requireOnly(body, TOP, "уведомление");
        String eventId = text(body, "eventId", 128);
        String type = text(body, "type", 64);
        Instant occurredAt = timestamp(body.get("occurredAt"));
        if (!(body.get("data") instanceof Map<?, ?> raw)) {
            throw bad("Поле data обязательно и должно быть объектом.");
        }
        Map<String, Object> data = stringKeys(raw);
        requireOnly(data, DATA, "data");
        String paymentId = text(data, "paymentId", 128);
        UUID orderId = orderId(data.get("orderId"));
        if (!(data.get("amount") instanceof Map<?, ?> money)) {
            throw bad("Поле data.amount обязательно и должно быть объектом.");
        }
        Map<String, Object> fields = stringKeys(money);
        requireOnly(fields, Set.of("amount", "currency"), "data.amount");
        if (!(fields.get("amount") instanceof Integer) && !(fields.get("amount") instanceof Long)) {
            throw bad("Поле data.amount.amount должно быть целым числом копеек.");
        }
        long amount = ((Number) fields.get("amount")).longValue();
        if (amount < 0 || amount > MAX_AMOUNT) {
            throw bad("Поле data.amount.amount вне допустимого диапазона.");
        }
        if (!(fields.get("currency") instanceof String currency) || !currency.matches("^[A-Z]{3}$")) {
            throw bad("Поле data.amount.currency должно быть кодом валюты из трёх заглавных букв.");
        }
        String refundId = data.containsKey("refundId") ? text(data, "refundId", 128) : null;
        String reason = null;
        if (data.containsKey("failureReason")) {
            if (!(data.get("failureReason") instanceof String text) || !REASONS.contains(text)) {
                throw bad("Поле data.failureReason вне допустимого перечисления.");
            }
            reason = text;
        }
        return new GatewayNotification(eventId, type, occurredAt, paymentId, orderId, amount, currency, refundId, reason);
    }

    private static void requireOnly(Map<String, Object> object, Set<String> allowed, String where) {
        for (String name : object.keySet()) {
            if (!allowed.contains(name)) {
                throw bad("В объекте «" + where + "» есть лишнее поле.");
            }
        }
    }

    private static Map<String, Object> stringKeys(Map<?, ?> raw) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    private static String text(Map<String, Object> object, String name, int max) {
        if (!(object.get(name) instanceof String value) || value.isEmpty() || value.length() > max) {
            throw bad("Поле " + name + " обязательно: непустая строка не длиннее " + max + " символов.");
        }
        return value;
    }

    private static Instant timestamp(Object raw) {
        if (!(raw instanceof String text) || Timestamps.parse(text).isEmpty()) {
            throw bad("Поле occurredAt должно быть моментом времени RFC 3339.");
        }
        return Timestamps.parse(text).orElseThrow();
    }

    private static UUID orderId(Object raw) {
        if (!(raw instanceof String text) || !ID.matcher(text).matches()) {
            throw bad("Поле data.orderId должно быть идентификатором UUID в нижнем регистре.");
        }
        return UUID.fromString(text);
    }

    private static ProblemException bad(String detail) {
        return new ProblemException(ProblemType.BAD_REQUEST, detail);
    }
}
