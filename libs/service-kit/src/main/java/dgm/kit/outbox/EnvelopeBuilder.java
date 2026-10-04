package dgm.kit.outbox;

import dgm.kit.json.Json;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Собирает конверт события в структурном режиме CloudEvents 1.0 из строки Outbox (conventions.md, раздел 11.2). Тело {@code data} это
 * текст столбца {@code payload} без разбора и пересборки: числа и порядок полей остаются такими, как записал издатель.
 */
public final class EnvelopeBuilder {

    /** Предел размера сообщения Kafka (conventions.md, раздел 11.2). */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private final String source;

    public EnvelopeBuilder(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Не задано имя сервиса-издателя (source)");
        }
        this.source = source;
    }

    /**
     * @throws PublishException если заголовки строки неполны или сообщение больше предела: такое событие неисправимо
     */
    public OutgoingEvent build(OutboxRow row) throws PublishException {
        Map<String, Object> headers;
        try {
            headers = Json.parseObject(row.headers());
        } catch (RuntimeException e) {
            throw new PublishException("Заголовки события " + row.eventId() + " не разбираются", e, true);
        }
        Object schemaVersion = headers.get("schemaversion");
        Object traceparent = headers.get("traceparent");
        Object correlationId = headers.get("correlationid");
        if (!(schemaVersion instanceof Integer version) || version < 1 || !(traceparent instanceof String trace)
                || !(correlationId instanceof String correlation)) {
            throw new PublishException("В заголовках события " + row.eventId() + " нет schemaversion, traceparent или correlationid", true);
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("specversion", "1.0");
        attributes.put("id", row.eventId().toString());
        attributes.put("source", source);
        attributes.put("type", row.eventType());
        attributes.put("time", DateTimeFormatter.ISO_INSTANT.format(row.createdAt()));
        attributes.put("subject", row.aggregateId());
        attributes.put("datacontenttype", "application/json");
        attributes.put("schemaversion", version);
        attributes.put("aggregatetype", row.aggregateType());
        attributes.put("traceparent", trace);
        attributes.put("correlationid", correlation);
        String head = Json.write(attributes);
        // Склейка вместо разбора тела: "{...}" становится "{...,"data":<payload>}"
        String value = head.substring(0, head.length() - 1) + ",\"data\":" + row.payload() + "}";
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new PublishException("Событие " + row.eventId() + " больше " + MAX_MESSAGE_BYTES + " байт", true);
        }
        return new OutgoingEvent(row.topic(), row.aggregateId(), value, row.eventType(), trace, row.eventId());
    }
}
