package dgm.platform.notification.controller;

import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.Timestamps;
import dgm.kit.web.RequestBodies;
import dgm.platform.notification.domain.MailStatus;
import dgm.platform.notification.domain.MailStatusNotice;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Чтение тела уведомления о статусе письма ({@code receiveMailStatus}, схема {@code MailProviderNotification}). Не JSON и нарушение схемы
 * это 400, не тот {@code Content-Type} 415. Тело читается после проверки подписи, поэтому сюда попадает только то, что подписал провайдер.
 * Статус перечисление открытое: незнакомый статус разбирается как {@link MailStatus#OTHER} и ничего не меняет, а причина отказа обязана быть
 * из перечисления схемы. Значения в сообщения об ошибках не попадают.
 */
public final class MailStatusBodies {

    /** Предел тела 64 КБ, как в описании операции и на шлюзе платформы. */
    public static final long MAX_BODY = 64L * 1024;

    private static final Set<String> FIELDS = Set.of("messageId", "status", "occurredAt", "reason");
    private static final Set<String> REASONS = Set.of("address_rejected", "other");

    private MailStatusBodies() {
    }

    public static MailStatusNotice read(HttpServletRequest request) throws IOException {
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

    public static MailStatusNotice parse(Map<String, Object> body) {
        for (String name : body.keySet()) {
            if (!FIELDS.contains(name)) {
                throw bad("В уведомлении есть лишнее поле.");
            }
        }
        if (!(body.get("messageId") instanceof String messageId) || messageId.isEmpty() || messageId.length() > 128) {
            throw bad("Поле messageId обязательно: непустая строка не длиннее 128 символов.");
        }
        if (!(body.get("status") instanceof String status) || status.isEmpty() || status.length() > 64) {
            throw bad("Поле status обязательно: непустая строка.");
        }
        if (!(body.get("occurredAt") instanceof String occurred) || Timestamps.parse(occurred).isEmpty()) {
            throw bad("Поле occurredAt должно быть моментом времени RFC 3339.");
        }
        Instant occurredAt = Timestamps.parse(occurred).orElseThrow();
        String reason = null;
        if (body.containsKey("reason")) {
            if (!(body.get("reason") instanceof String text) || !REASONS.contains(text)) {
                throw bad("Поле reason вне допустимого перечисления.");
            }
            reason = text;
        }
        return new MailStatusNotice(messageId, MailStatus.parse(status), occurredAt, reason);
    }

    private static ProblemException bad(String detail) {
        return new ProblemException(ProblemType.BAD_REQUEST, detail);
    }
}
