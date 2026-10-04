package dgm.kit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.EventEnvelope;
import dgm.kit.outbox.EventPublisher;
import dgm.kit.outbox.KafkaEventPublisher;
import dgm.kit.outbox.OutboxCleaner;
import dgm.kit.outbox.OutboxRelay;
import dgm.kit.outbox.OutboxSettings;
import dgm.kit.outbox.OutboxWriter;
import dgm.kit.outbox.OutgoingEvent;
import dgm.kit.outbox.PublishException;
import dgm.kit.testing.Stand;
import dgm.kit.time.Clocks;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Публикатор Outbox на настоящих PostgreSQL и Kafka стенда: порядок, лидер, повторы, парковка, очистка, атомарность записи. */
@Tag("integration")
class OutboxRelayIT {

    private static DataSource db;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static OutboxWriter writer;
    private static EventPublisher kafka;

    @BeforeAll
    static void connect() {
        db = Env.orderDb();
        jdbc = new JdbcTemplate(db);
        tx = new TransactionTemplate(new DataSourceTransactionManager(db));
        writer = new OutboxWriter(jdbc, Clocks.system());
        kafka = KafkaEventPublisher.create(Stand.kafkaBootstrap(), Stand.kafkaSecurity("order-service"), "order-service-it");
    }

    @AfterAll
    static void close() {
        kafka.close();
    }

    @BeforeEach
    void cleanTable() {
        jdbc.update("delete from outbox");
    }

    /** Запись события в своей транзакции, как делает бизнес-код. */
    private static UUID write(String aggregate, int n) {
        return tx.execute(status -> writer.write("order.events", "Order", aggregate, "order.paid", 1, Map.of("n", n)));
    }

    private static int count(String where) {
        return jdbc.queryForObject("select count(*) from outbox where " + where, Integer.class);
    }

    private static OutboxRelay relay(EventPublisher publisher, java.time.Clock clock, SimpleMeterRegistry metrics) {
        return new OutboxRelay(db, publisher, "order-service", OutboxSettings.defaults(), clock, metrics);
    }

    /** Подставной отправитель: заданные события не отправляются, остальные запоминаются. */
    private static final class ScriptedPublisher implements EventPublisher {
        final Set<UUID> failFor = new HashSet<>();
        final Map<UUID, Integer> calls = new HashMap<>();
        final List<UUID> delivered = new ArrayList<>();

        @Override
        public void publish(OutgoingEvent event) throws PublishException {
            calls.merge(event.eventId(), 1, Integer::sum);
            if (failFor.contains(event.eventId())) {
                throw new PublishException("брокер недоступен", false);
            }
            delivered.add(event.eventId());
        }

        @Override
        public void close() {
            // нечего закрывать
        }
    }

    @Test
    void publishesInOrderWithCloudEventsEnvelopeAndKafkaHeaders() throws Exception {
        String aggregate = Env.subject("order-seq");
        List<UUID> ids = List.of(write(aggregate, 1), write(aggregate, 2), write(aggregate, 3));

        OutboxRelay relay = relay(kafka, Clocks.system(), new SimpleMeterRegistry());
        try {
            assertTrue(Env.await(Duration.ofSeconds(30), () -> {
                relay.runOnce();
                return count("published_at is null") == 0;
            }), "все строки отправлены");
        } finally {
            relay.close();
        }
        assertEquals(3, count("published_at is not null and attempts = 0"));

        List<ConsumerRecord<String, String>> records = Env.readTopic("order.events", r -> aggregate.equals(r.key()), 3, Duration.ofSeconds(30));
        assertEquals(3, records.size());
        int partition = records.get(0).partition();
        for (int i = 0; i < 3; i++) {
            ConsumerRecord<String, String> r = records.get(i);
            assertEquals(partition, r.partition(), "один агрегат в одной партиции");
            assertEquals("order.paid", Env.header(r, "ce_type"));
            assertTrue(Env.header(r, "traceparent").matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$"));
            EventEnvelope e = EventEnvelope.parse(r.value());
            assertEquals(ids.get(i), e.id(), "порядок событий агрегата сохранён");
            assertEquals("order-service", e.source());
            assertEquals(aggregate, e.subject());
            assertEquals("Order", e.aggregateType());
            assertEquals(1, e.schemaVersion());
            assertEquals(i + 1, e.data().get("n"));
            assertEquals(Env.header(r, "traceparent"), e.traceparent());
        }
    }

    @Test
    void onlyOneRelayPublishesAndAnotherTakesOverWhenLeaderStops() throws Exception {
        ScriptedPublisher publisher = new ScriptedPublisher();
        OutboxRelay a = relay(publisher, Clocks.system(), new SimpleMeterRegistry());
        OutboxRelay b = relay(publisher, Clocks.system(), new SimpleMeterRegistry());
        try {
            UUID first = write(Env.subject("leader"), 1);
            assertEquals(1, a.runOnce());
            assertTrue(a.isLeader());

            UUID second = write(Env.subject("leader"), 2);
            assertEquals(0, b.runOnce(), "второй экземпляр не публикует, пока жив лидер");
            assertFalse(b.isLeader());
            assertEquals(List.of(first), publisher.delivered);

            a.close();
            assertEquals(1, b.runOnce(), "после ухода лидера блокировку берёт другой экземпляр");
            assertTrue(b.isLeader());
            assertEquals(List.of(first, second), publisher.delivered);
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void failedSendKeepsOrderBacksOffAndParksPoisonRowAfterTenAttempts() throws Exception {
        ManualClock clock = new ManualClock(Instant.parse("2026-10-04T10:00:00Z"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ScriptedPublisher publisher = new ScriptedPublisher();
        UUID poison = write(Env.subject("poison"), 1);
        UUID next = write(Env.subject("poison"), 2);
        publisher.failFor.add(poison);
        OutboxRelay relay = relay(publisher, clock, metrics);
        try {
            assertEquals(0, relay.runOnce());
            assertEquals(1, publisher.calls.get(poison));
            assertNull(publisher.calls.get(next), "следующая строка не обгоняет неотправленную");
            assertEquals(1, jdbc.queryForObject("select attempts from outbox where event_id = ?", Integer.class, poison));

            assertEquals(0, relay.runOnce(), "пауза после неудачи: публикатор не обращается к брокеру");
            assertEquals(1, publisher.calls.get(poison));

            for (int attempt = 2; attempt <= 10; attempt++) {
                clock.advance(Duration.ofSeconds(31));
                relay.runOnce();
            }
            assertEquals(10, publisher.calls.get(poison));
            assertEquals(List.of(next), publisher.delivered, "после парковки публикатор идёт дальше");
            assertNotNull(jdbc.queryForObject("select failed_at from outbox where event_id = ?", java.sql.Timestamp.class, poison));
            assertEquals(10, jdbc.queryForObject("select attempts from outbox where event_id = ?", Integer.class, poison));
            assertEquals(1, count("published_at is not null"));
            assertEquals(1, metrics.get("outbox_parked_total").counter().count());
            assertEquals(10, metrics.get("outbox_publish_failures_total").counter().count());

            clock.advance(Duration.ofMinutes(5));
            assertEquals(0, relay.runOnce(), "припаркованная строка больше не отправляется");
            assertEquals(10, publisher.calls.get(poison));
        } finally {
            relay.close();
        }
    }

    @Test
    void rowWithoutRequiredHeadersIsParkedAtOnceAndNextRowIsSent() throws Exception {
        UUID bad = UUID.randomUUID();
        jdbc.update("insert into outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload, headers) "
                + "values (?, 'Order', ?, 'order.paid', 'order.events', '{}'::jsonb, '{}'::jsonb)", bad, Env.subject("bad-headers"));
        UUID good = write(Env.subject("bad-headers"), 2);
        ScriptedPublisher publisher = new ScriptedPublisher();
        OutboxRelay relay = relay(publisher, Clocks.system(), new SimpleMeterRegistry());
        try {
            assertEquals(1, relay.runOnce());
        } finally {
            relay.close();
        }
        assertNull(publisher.calls.get(bad), "в брокер неверная строка не уходит");
        assertEquals(List.of(good), publisher.delivered);
        assertEquals(1, jdbc.queryForObject("select attempts from outbox where event_id = ? and failed_at is not null", Integer.class, bad));
    }

    @Test
    void messageOver64KbIsParkedWithoutBlockingTheQueue() throws Exception {
        UUID big = tx.execute(status -> writer.write("order.events", "Order", Env.subject("big"), "order.paid", 1,
                Map.of("blob", "x".repeat(70 * 1024))));
        UUID small = write(Env.subject("big"), 2);
        ScriptedPublisher publisher = new ScriptedPublisher();
        OutboxRelay relay = relay(publisher, Clocks.system(), new SimpleMeterRegistry());
        try {
            assertEquals(1, relay.runOnce());
        } finally {
            relay.close();
        }
        assertNull(publisher.calls.get(big));
        assertEquals(List.of(small), publisher.delivered);
        assertEquals(1, count("event_id = '" + big + "' and failed_at is not null"));
    }

    @Test
    void cleanerRemovesOnlyPublishedRowsOlderThanThreeDays() {
        String insert = "insert into outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload, headers, published_at, failed_at) "
                + "values (?, 'Order', 'x', 'order.paid', 'order.events', '{}'::jsonb, '{}'::jsonb, "
                + "now() - make_interval(hours => ?), null)";
        UUID oldPublished = UUID.randomUUID();
        UUID recentPublished = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        UUID parked = UUID.randomUUID();
        jdbc.update(insert, oldPublished, 96);
        jdbc.update(insert, recentPublished, 1);
        jdbc.update("insert into outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload, headers) "
                + "values (?, 'Order', 'x', 'order.paid', 'order.events', '{}'::jsonb, '{}'::jsonb)", pending);
        jdbc.update("insert into outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload, headers, failed_at) "
                + "values (?, 'Order', 'x', 'order.paid', 'order.events', '{}'::jsonb, '{}'::jsonb, now() - interval '10 days')", parked);

        assertEquals(1, new OutboxCleaner(jdbc).clean());

        Set<UUID> left = new HashSet<>(jdbc.queryForList("select event_id from outbox", UUID.class));
        assertEquals(Set.of(recentPublished, pending, parked), left);
    }

    @Test
    void cleanerWorksInBatchesOfThousand() {
        jdbc.update("insert into outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload, headers, published_at) "
                + "select gen_random_uuid(), 'Order', 'x', 'order.paid', 'order.events', '{}'::jsonb, '{}'::jsonb, now() - interval '5 days' "
                + "from generate_series(1, 2500)");
        assertEquals(2500, new OutboxCleaner(jdbc).clean());
        assertEquals(0, count("true"));
    }

    @Test
    void eventExistsOnlyIfTheDataTransactionCommitted() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            writer.write("order.events", "Order", Env.subject("atomic"), "order.paid", 1, Map.of("n", 1));
            throw new IllegalStateException("сбой после записи события");
        }));
        assertEquals(0, count("true"), "откат данных откатывает и событие");

        tx.executeWithoutResult(status -> {
            writer.write("order.events", "Order", Env.subject("atomic"), "order.paid", 1, Map.of("n", 2));
            status.setRollbackOnly();
        });
        assertEquals(0, count("true"));

        write(Env.subject("atomic"), 3);
        assertEquals(1, count("published_at is null and failed_at is null"));
    }

    @Test
    void writerRefusesWithoutTransaction() {
        assertThrows(IllegalStateException.class, () -> writer.write("order.events", "Order", Env.subject("none"), "order.paid", 1, Map.of()));
        assertEquals(0, count("true"));
    }
}
