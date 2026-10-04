package dgm.kit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.EventConsumer;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.EventProcessor;
import dgm.kit.events.HandleResult;
import dgm.kit.events.JdbcProcessedEvents;
import dgm.kit.events.KafkaDlqSink;
import dgm.kit.events.RetryableEventException;
import dgm.kit.json.Json;
import dgm.kit.kafka.KafkaClients;
import dgm.kit.outbox.EnvelopeBuilder;
import dgm.kit.outbox.KafkaEventPublisher;
import dgm.kit.outbox.OutboxRelay;
import dgm.kit.outbox.OutboxRow;
import dgm.kit.outbox.OutboxSettings;
import dgm.kit.outbox.OutboxWriter;
import dgm.kit.outbox.OutgoingEvent;
import dgm.kit.testing.Stand;
import dgm.kit.time.Clocks;
import dgm.kit.trace.TraceContext;
import dgm.kit.trace.TraceContexts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Потребитель событий от Kafka до базы на настоящем стенде. Цепочка: Outbox {@code order_db} → публикатор → {@code order.events} →
 * потребитель {@code inventory-service} → обработчик, который в той же транзакции пишет в Outbox {@code inventory_db}.
 */
@Tag("integration")
class EventConsumerIT {

    private static final String CONSUMER = "reservation-handler-it";

    private static JdbcTemplate orderJdbc;
    private static JdbcTemplate inventoryJdbc;
    private static DataSource orderDb;
    private static ReservationHandler handler;
    private static SimpleMeterRegistry metrics;
    private static EventConsumer consumer;
    private static KafkaProducer<String, String> producer;
    private static KafkaDlqSink dlqSink;

    /** Обработчик теста: считает вызовы по идентификатору события, пишет реакцию в Outbox и по заказу может падать. */
    private static final class ReservationHandler implements EventHandler {
        final Map<UUID, Integer> calls = new ConcurrentHashMap<>();
        final Set<String> failFor = ConcurrentHashMap.newKeySet();
        private final OutboxWriter outbox;

        ReservationHandler(OutboxWriter outbox) {
            this.outbox = outbox;
        }

        @Override
        public String name() {
            return CONSUMER;
        }

        @Override
        public String topic() {
            return "order.events";
        }

        @Override
        public Set<String> types() {
            return Set.of("order.paid");
        }

        @Override
        public int maxSchemaVersion() {
            return 1;
        }

        @Override
        public HandleResult handle(EventEnvelope event) {
            if (!event.subject().startsWith("it-" + Env.RUN + "-")) {
                return HandleResult.ignored("other-run");
            }
            calls.merge(event.id(), 1, Integer::sum);
            // Реакция пишется в той же транзакции, что и отметка processed_event
            outbox.write("inventory.events", "Reservation", event.subject(), "stock.reserved", 1, Map.of("orderId", event.subject()));
            if (failFor.contains(event.subject())) {
                throw new RetryableEventException("имитация временного сбоя");
            }
            return HandleResult.done();
        }
    }

    @BeforeAll
    static void start() {
        orderDb = Env.orderDb();
        orderJdbc = new JdbcTemplate(orderDb);
        DataSource inventoryDb = Env.inventoryDb();
        inventoryJdbc = new JdbcTemplate(inventoryDb);
        TransactionTemplate inventoryTx = new TransactionTemplate(new DataSourceTransactionManager(inventoryDb));
        inventoryJdbc.update("delete from outbox");
        inventoryJdbc.update("delete from processed_event where consumer = ?", CONSUMER);

        handler = new ReservationHandler(new OutboxWriter(inventoryJdbc, Clocks.system()));
        metrics = new SimpleMeterRegistry();
        dlqSink = new KafkaDlqSink(KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("inventory-service"), "inventory-service-dlq-it"),
                Duration.ofSeconds(20));
        EventProcessor processor = new EventProcessor("inventory-service", List.of(handler), new JdbcProcessedEvents(inventoryJdbc), inventoryTx,
                dlqSink, List.of(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(100)), Clocks.system(),
                d -> Thread.sleep(d.toMillis()), metrics);
        consumer = new EventConsumer(KafkaClients.consumer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("inventory-service"), "inventory-service",
                "inventory-service-it"), processor, Duration.ofMillis(300), Duration.ofSeconds(1));
        consumer.start();

        producer = KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("order-service"), "order-service-raw-it");
    }

    @AfterAll
    static void stop() {
        consumer.close();
        producer.close(Duration.ofSeconds(5));
        dlqSink.close();
    }

    /** Конверт, собранный тем же кодом, что собирает публикатор, и отправленный в тему напрямую (для дублей и сбоев). */
    private static OutgoingEvent envelope(UUID id, String subject, String type, int version) throws Exception {
        String headers = "{\"schemaversion\": " + version + ", \"traceparent\": \"" + TraceContext.fresh().traceparent()
                + "\", \"correlationid\": \"" + UUID.randomUUID() + "\"}";
        return new EnvelopeBuilder("order-service").build(new OutboxRow(1, id, "Order", subject, type, "order.events", "{\"total\": 1990}", headers,
                Instant.now(), 0));
    }

    private static void send(OutgoingEvent e) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(e.topic(), e.key(), e.value());
        record.headers().add("ce_type", e.type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent", e.traceparent().getBytes(StandardCharsets.UTF_8));
        producer.send(record).get(20, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static boolean processed(UUID id) {
        return count(inventoryJdbc, "select count(*) from processed_event where consumer = ? and event_id = ?", CONSUMER, id) == 1;
    }

    @Test
    void eventTravelsFromOrderOutboxToInventoryHandlerWithTraceAndInOneTransaction() throws Exception {
        String subject = Env.subject("e2e");
        String correlation = UUID.randomUUID().toString();
        TraceContext origin = TraceContext.incoming(null, correlation);
        TransactionTemplate orderTx = new TransactionTemplate(new DataSourceTransactionManager(orderDb));
        OutboxWriter orderOutbox = new OutboxWriter(orderJdbc, Clocks.system());
        TraceContexts.Scope scope = TraceContexts.open(origin);
        UUID eventId;
        try {
            eventId = orderTx.execute(s -> orderOutbox.write("order.events", "Order", subject, "order.paid", 1, Map.of("orderId", subject)));
        } finally {
            scope.close();
        }

        OutboxRelay relay = new OutboxRelay(orderDb, KafkaEventPublisher.create(Stand.kafkaBootstrap(), Stand.kafkaSecurity("order-service"),
                "order-service-relay-it"), "order-service", OutboxSettings.defaults(), Clocks.system(), new SimpleMeterRegistry());
        try {
            assertTrue(Env.await(Duration.ofSeconds(30), () -> {
                relay.runOnce();
                return count(orderJdbc, "select count(*) from outbox where event_id = ? and published_at is not null", eventId) == 1;
            }), "событие опубликовано");
        } finally {
            relay.close();
        }

        assertTrue(Env.await(Duration.ofSeconds(60), () -> processed(eventId)), "потребитель обработал событие");
        assertEquals(1, handler.calls.get(eventId));

        Map<String, Object> reaction = inventoryJdbc.queryForMap("select event_type, topic, aggregate_id, headers::text as headers from outbox where aggregate_id = ?",
                subject);
        assertEquals("stock.reserved", reaction.get("event_type"));
        assertEquals("inventory.events", reaction.get("topic"));
        Map<String, Object> headers = Json.parseObject((String) reaction.get("headers"));
        assertEquals(correlation, headers.get("correlationid"), "сквозной идентификатор дошёл до реакции");
        assertTrue(((String) headers.get("traceparent")).startsWith("00-" + origin.traceId() + "-"), "трасса запроса сохранена через Kafka");
    }

    @Test
    void sameEventDeliveredTwiceIsHandledOnce() throws Exception {
        String subject = Env.subject("duplicate");
        UUID id = UUID.randomUUID();
        OutgoingEvent e = envelope(id, subject, "order.paid", 1);
        send(e);
        send(e);

        assertTrue(Env.await(Duration.ofSeconds(60), () -> metrics.find("events_processed_total").tag("result", "duplicate").counter() != null), "повтор обнаружен");
        assertTrue(processed(id));
        assertEquals(1, handler.calls.get(id));
        assertEquals(1, count(inventoryJdbc, "select count(*) from outbox where aggregate_id = ?", subject), "реакция записана один раз");
    }

    @Test
    void failingHandlerIsRetriedThenSentToDlqAndEverythingRolledBackWhileReadingContinues() throws Exception {
        String badSubject = Env.subject("fails");
        String goodSubject = Env.subject("after-fail");
        handler.failFor.add(badSubject);
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        send(envelope(bad, badSubject, "order.paid", 1));
        send(envelope(good, goodSubject, "order.paid", 1));

        List<ConsumerRecord<String, String>> dlq = Env.readTopic("order.events.dlq", r -> badSubject.equals(r.key()), 1, Duration.ofSeconds(60));
        assertEquals(1, dlq.size(), "сообщение попало в order.events.dlq");
        ConsumerRecord<String, String> d = dlq.get(0);
        assertEquals("order.events", Env.header(d, "dlq-original-topic"));
        assertEquals("4", Env.header(d, "dlq-attempts"));
        assertEquals(CONSUMER, Env.header(d, "dlq-consumer"));
        assertEquals("dgm.kit.events.RetryableEventException", Env.header(d, "dlq-error-class"));
        assertTrue(Env.header(d, "dlq-failed-at").endsWith("Z"));
        assertTrue(Long.parseLong(Env.header(d, "dlq-original-offset")) >= 0);
        assertEquals(EventEnvelope.parse(d.value()).id(), bad, "значение записи не изменено");

        assertEquals(4, handler.calls.get(bad), "первая попытка и три повтора");
        assertTrue(Env.await(Duration.ofSeconds(60), () -> processed(good)), "чтение продолжилось, следующее событие обработано");
        assertEquals(false, processed(bad), "строка processed_event откатилась");
        assertEquals(0, count(inventoryJdbc, "select count(*) from outbox where aggregate_id = ?", badSubject), "реакция откатилась вместе с обработчиком");
        assertEquals(1, count(inventoryJdbc, "select count(*) from outbox where aggregate_id = ?", goodSubject));
    }

    @Test
    void invalidMessageGoesToDlqAtOnce() throws Exception {
        String subject = Env.subject("invalid");
        ProducerRecord<String, String> record = new ProducerRecord<>("order.events", subject, "{\"hello\":\"world\"}");
        producer.send(record).get(20, java.util.concurrent.TimeUnit.SECONDS);

        List<ConsumerRecord<String, String>> dlq = Env.readTopic("order.events.dlq", r -> subject.equals(r.key()), 1, Duration.ofSeconds(60));
        assertEquals(1, dlq.size());
        assertEquals("1", Env.header(dlq.get(0), "dlq-attempts"));
        assertEquals("inventory-service", Env.header(dlq.get(0), "dlq-consumer"));
        assertEquals("dgm.kit.events.InvalidEventException", Env.header(dlq.get(0), "dlq-error-class"));
        assertEquals("{\"hello\":\"world\"}", dlq.get(0).value());
    }

    @Test
    void unknownTypeAndNewerSchemaVersionAreSkippedWithoutDlq() throws Exception {
        String subject = Env.subject("skipped");
        UUID otherType = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        UUID normal = UUID.randomUUID();
        send(envelope(otherType, subject, "order.cancelled", 1));
        send(envelope(newer, subject, "order.paid", 7));
        send(envelope(normal, subject, "order.paid", 1));

        assertTrue(Env.await(Duration.ofSeconds(60), () -> processed(normal)), "обычное событие обработано после пропущенных");
        assertNull(handler.calls.get(otherType));
        assertNull(handler.calls.get(newer));
        assertEquals(false, processed(newer));
        assertTrue(metrics.find("events_skipped_total").tag("reason", "unknown-type").counter() != null);
        assertTrue(metrics.find("events_skipped_total").tag("reason", "unknown-version").counter() != null);
        assertEquals(0, Env.readTopic("order.events.dlq", r -> subject.equals(r.key()), 1, Duration.ofSeconds(3)).size());
    }
}
