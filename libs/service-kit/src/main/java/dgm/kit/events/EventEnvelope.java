package dgm.kit.events;

import dgm.kit.json.Json;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Конверт события, прочитанный из сообщения Kafka (CloudEvents 1.0, структурный режим, conventions.md, раздел 11.2).
 *
 * @param id            идентификатор события (ключ дедупликации)
 * @param source        сервис-издатель
 * @param type          тип события
 * @param time          момент фиксации транзакции издателя
 * @param subject       идентификатор агрегата (равен ключу записи)
 * @param schemaVersion старшая версия схемы данных
 * @param aggregateType тип агрегата
 * @param traceparent   W3C Trace Context запроса, в котором возникло событие
 * @param correlationId сквозной идентификатор
 * @param data          данные события
 */
public record EventEnvelope(UUID id, String source, String type, Instant time, String subject, int schemaVersion,
                            String aggregateType, String traceparent, String correlationId, Map<String, Object> data) {

    /** Предел размера сообщения (conventions.md, раздел 11.2). */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private static final Pattern TYPE = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$");
    private static final Pattern TRACEPARENT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    public EventEnvelope {
        data = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }

    /** Разбирает и проверяет конверт. Любое нарушение даёт {@link InvalidEventException}. */
    public static EventEnvelope parse(String text) throws InvalidEventException {
        if (text == null) {
            throw new InvalidEventException("Пустое значение записи");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new InvalidEventException("Сообщение больше " + MAX_MESSAGE_BYTES + " байт");
        }
        Map<String, Object> doc;
        try {
            doc = Json.parseObject(text);
        } catch (RuntimeException e) {
            throw new InvalidEventException("Значение записи не является JSON-объектом", e);
        }
        if (!"1.0".equals(doc.get("specversion"))) {
            throw new InvalidEventException("specversion должен быть 1.0");
        }
        UUID id = uuid(doc, "id");
        String source = text(doc, "source");
        String type = text(doc, "type");
        if (!TYPE.matcher(type).matches()) {
            throw new InvalidEventException("type не соответствует формату <область>.<событие>: " + type);
        }
        Instant time;
        try {
            time = java.time.OffsetDateTime.parse(text(doc, "time")).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidEventException("time не разбирается как RFC 3339", e);
        }
        String subject = text(doc, "subject");
        if (!"application/json".equals(doc.get("datacontenttype"))) {
            throw new InvalidEventException("datacontenttype должен быть application/json");
        }
        Object version = doc.get("schemaversion");
        if (!(version instanceof Integer v) || v < 1) {
            throw new InvalidEventException("schemaversion должен быть целым числом от 1");
        }
        String aggregateType = text(doc, "aggregatetype");
        String traceparent = text(doc, "traceparent");
        if (!TRACEPARENT.matcher(traceparent).matches()) {
            throw new InvalidEventException("traceparent не соответствует W3C Trace Context");
        }
        String correlationId = text(doc, "correlationid");
        try {
            UUID.fromString(correlationId);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("correlationid должен быть UUID", e);
        }
        Object data = doc.get("data");
        if (!(data instanceof Map<?, ?> raw)) {
            throw new InvalidEventException("data должно быть объектом");
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            copy.put(String.valueOf(e.getKey()), e.getValue());
        }
        return new EventEnvelope(id, source, type, time, subject, v, aggregateType, traceparent, correlationId, copy);
    }

    private static String text(Map<String, Object> doc, String key) throws InvalidEventException {
        Object value = doc.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new InvalidEventException("Нет обязательного атрибута " + key);
        }
        return s;
    }

    private static UUID uuid(Map<String, Object> doc, String key) throws InvalidEventException {
        String value = text(doc, key);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("Атрибут " + key + " должен быть UUID", e);
        }
    }
}
