package dgm.kit.outbox;

import dgm.kit.id.Uuids;
import dgm.kit.json.Json;
import dgm.kit.trace.TraceContext;
import dgm.kit.trace.TraceContexts;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Запись события в таблицу {@code outbox} (ADR-005, conventions.md, раздел 11). Вызывается внутри транзакции, которая меняет данные:
 * без транзакции запись отклоняется, потому что событие без общей фиксации с данными теряется или появляется без изменения.
 * Прямая отправка в Kafka из прикладного кода запрещена правилом ArchUnit.
 */
public final class OutboxWriter {

    private static final String INSERT = "insert into outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload, headers) "
            + "values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Записывает событие.
     *
     * @param topic         тема Kafka
     * @param aggregateType тип агрегата (Order, Payment)
     * @param aggregateId   идентификатор агрегата, он же ключ записи Kafka и {@code subject} конверта
     * @param eventType     тип события, например {@code order.paid}
     * @param schemaVersion старшая версия схемы данных события, от 1
     * @param data          данные события (объект), без персональных данных и значений ключей
     * @return идентификатор события (UUIDv7): он же {@code id} конверта и ключ дедупликации потребителя
     */
    public UUID write(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Событие записывается только внутри транзакции, меняющей данные (ADR-005)");
        }
        requireText(topic, "topic");
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        requireText(eventType, "eventType");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("Версия схемы события начинается с 1");
        }
        Objects.requireNonNull(data, "data");

        TraceContext trace = TraceContexts.currentOrFresh().child();
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("schemaversion", schemaVersion);
        headers.put("traceparent", trace.traceparent());
        headers.put("correlationid", trace.correlationId());

        UUID eventId = Uuids.v7(clock);
        jdbc.update(INSERT, eventId, aggregateType, aggregateId, eventType, topic, Json.write(data), Json.write(headers));
        return eventId;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Не задано поле " + name);
        }
    }
}
