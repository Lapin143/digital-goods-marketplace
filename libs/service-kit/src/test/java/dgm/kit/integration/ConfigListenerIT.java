package dgm.kit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.config.ConfigListener;
import dgm.kit.config.ConfigRegistry;
import dgm.kit.config.ConfigValue;
import dgm.kit.config.KafkaConfigFeed;
import dgm.kit.kafka.KafkaClients;
import dgm.kit.outbox.EnvelopeBuilder;
import dgm.kit.outbox.OutboxRow;
import dgm.kit.outbox.OutgoingEvent;
import dgm.kit.testing.Stand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Слушатель параметров на настоящей Kafka стенда: сообщения {@code config.changed} пишутся в {@code platform.config} от имени
 * {@code platform-service} (право записи), слушатель читает от имени {@code order-service} (право чтения). У каждого теста свой ключ с меткой
 * запуска, поэтому значения платформы и других тестов в теме не мешают: чужие ключи слушатель игнорирует.
 */
@Tag("integration")
@Tag("TC-808")
class ConfigListenerIT {

    private static final String TOPIC = "platform.config";
    private static final Duration WAIT = Duration.ofSeconds(60);

    private static KafkaProducer<String, String> producer;
    private final List<ConfigListener> started = new ArrayList<>();

    @BeforeAll
    static void connect() {
        producer = KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("platform-service"), "platform-service-config-it");
    }

    @AfterAll
    static void disconnect() {
        producer.close(Duration.ofSeconds(5));
    }

    @AfterEach
    void stopListeners() {
        started.forEach(ConfigListener::close);
        started.clear();
    }

    /** Конверт, собранный тем же кодом, что собирает публикатор платформы. */
    private static OutgoingEvent change(String key, long value, int version) throws Exception {
        String payload = "{\"key\": \"" + key + "\", \"value\": " + value + ", \"version\": " + version
                + ", \"unit\": \"seconds\", \"changedBy\": \"5d3b1f79-8c2e-4a60-b4d8-6e0a2c4f1b37\"}";
        String headers = "{\"schemaversion\": 1, \"traceparent\": \"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01\", "
                + "\"correlationid\": \"" + UUID.randomUUID() + "\"}";
        return new EnvelopeBuilder("platform-service").build(new OutboxRow(1, UUID.randomUUID(), "Parameter", key, "config.changed", TOPIC, payload,
                headers, Instant.now(), 0));
    }

    private static void send(OutgoingEvent e) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(e.topic(), e.key(), e.value());
        record.headers().add("ce_type", e.type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent", e.traceparent().getBytes(StandardCharsets.UTF_8));
        producer.send(record).get(20, TimeUnit.SECONDS);
    }

    private static void publish(String key, long value, int version) throws Exception {
        send(change(key, value, version));
    }

    private ConfigListener listen(ConfigRegistry registry, SimpleMeterRegistry metrics) {
        ConfigListener listener = new ConfigListener(
                () -> new KafkaConfigFeed(KafkaClients.configConsumer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("order-service"),
                        "order-service-config-it-" + UUID.randomUUID()), TOPIC),
                registry, metrics, Duration.ofMillis(300), Duration.ofSeconds(1));
        started.add(listener);
        listener.start();
        return listener;
    }

    private static double count(SimpleMeterRegistry metrics, String name, String... tags) {
        var counter = metrics.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void newerVersionIsAppliedAndAnOlderOneAfterItIsSkipped() throws Exception {
        String key = Env.subject("order");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ConfigRegistry registry = new ConfigRegistry(List.of(), metrics);
        ConfigValue value = registry.register(key, 10, 1, 1000);
        listen(registry, metrics);

        publish(key, 30, 3);
        assertTrue(Env.await(WAIT, () -> value.get() == 30 && value.version() == 3), "версия 3 применена");

        publish(key, 20, 2);
        publish(key, 40, 4);
        assertTrue(Env.await(WAIT, () -> value.get() == 40 && value.version() == 4), "версия 4 применена после устаревшей");
        assertEquals(1, count(metrics, "config_rejected_total", "key", key, "reason", "stale-config"), "версия 2 пропущена как устаревшая");
        assertEquals(2, count(metrics, "config_applied_total", "key", key));
        assertEquals(4.0, metrics.get("config_version").tag("key", key).gauge().value());
    }

    @Test
    void aNewInstanceReadsTheTopicFromTheStartAndGetsTheCurrentValue() throws Exception {
        String key = Env.subject("restart");
        SimpleMeterRegistry firstMetrics = new SimpleMeterRegistry();
        ConfigRegistry first = new ConfigRegistry(List.of(), firstMetrics);
        ConfigValue before = first.register(key, 5, 1, 1000);
        ConfigListener running = listen(first, firstMetrics);
        publish(key, 50, 2);
        assertTrue(Env.await(WAIT, () -> before.get() == 50), "первый экземпляр получил значение");
        running.close();
        assertFalse(running.isRunning());

        publish(key, 60, 3);

        SimpleMeterRegistry secondMetrics = new SimpleMeterRegistry();
        ConfigRegistry second = new ConfigRegistry(List.of(), secondMetrics);
        ConfigValue after = second.register(key, 5, 1, 1000);
        listen(second, secondMetrics);
        assertTrue(Env.await(WAIT, () -> after.get() == 60 && after.version() == 3), "второй экземпляр прочитал тему с начала без запроса к платформе");
        assertTrue(count(secondMetrics, "config_applied_total", "key", key) >= 1, "значение применено слушателем, а не взято из настроек");
    }

    @Test
    void pinnedKeyKeepsItsValueWhileOthersAreApplied() throws Exception {
        String pinned = Env.subject("pinned");
        String free = Env.subject("free");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ConfigRegistry registry = new ConfigRegistry(List.of(pinned), metrics);
        ConfigValue pinnedValue = registry.register(pinned, 30, 1, 1000);
        ConfigValue freeValue = registry.register(free, 30, 1, 1000);
        listen(registry, metrics);

        publish(pinned, 900, 2);
        publish(free, 900, 2);

        assertTrue(Env.await(WAIT, () -> freeValue.get() == 900), "незакреплённый ключ применён");
        assertEquals(30, pinnedValue.get(), "закреплённый ключ остался со значением из настроек");
        assertEquals(0, pinnedValue.version());
        assertEquals(1, count(metrics, "config_pinned_total", "key", pinned));
    }

    @Test
    void aBrokenRecordIsSkippedAndTheNextOneIsStillApplied() throws Exception {
        String key = Env.subject("poison");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ConfigRegistry registry = new ConfigRegistry(List.of(), metrics);
        ConfigValue value = registry.register(key, 10, 1, 1000);
        listen(registry, metrics);

        producer.send(new ProducerRecord<>(TOPIC, key, "{не конверт")).get(20, TimeUnit.SECONDS);
        publish(key, 70, 2);

        assertTrue(Env.await(WAIT, () -> value.get() == 70), "запись после неисправной применена");
        assertTrue(count(metrics, "config_invalid_total") >= 1, "неисправная запись посчитана и пропущена");
    }

    @Test
    void messagesThatBreakTheBoundsAreNotAppliedAndDoNotStopTheListener() throws Exception {
        String key = Env.subject("bounds");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ConfigRegistry registry = new ConfigRegistry(List.of(), metrics);
        ConfigValue value = registry.register(key, 10, 5, 100);
        ConfigListener listener = listen(registry, metrics);

        publish(key, 1000, 2);
        publish(key, 50, 3);

        assertTrue(Env.await(WAIT, () -> value.get() == 50 && value.version() == 3), "допустимое значение применено после недопустимого");
        assertEquals(1, count(metrics, "config_rejected_total", "key", key, "reason", "out-of-range"));
        assertTrue(listener.isRunning());
    }

    @Test
    void invariantSevenIsKeptBetweenTheTwoKeysOfThePair() throws Exception {
        String reserve = Env.subject("reserve");
        String session = Env.subject("session");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ConfigRegistry registry = new ConfigRegistry(List.of(), metrics);
        ConfigValue reserveValue = registry.register(reserve, 900, 1, 86_400);
        ConfigValue sessionValue = registry.register(session, 720, 1, 86_400);
        registry.requireShorter(session, reserve);
        listen(registry, metrics);

        publish(reserve, 1000, 2);
        publish(session, 600, 2);
        publish(session, 1000, 3);
        publish(reserve, 600, 3);
        publish(session, 999, 4);

        assertTrue(Env.await(WAIT, () -> sessionValue.version() == 4), "допустимая правка сессии применена");
        assertEquals(999, sessionValue.get());
        assertEquals(1000, reserveValue.get(), "резерв, равный сессии, не применён");
        assertEquals(2, reserveValue.version());
        assertEquals(1, count(metrics, "config_rejected_total", "key", session, "reason", "inv-07"));
        assertEquals(1, count(metrics, "config_rejected_total", "key", reserve, "reason", "inv-07"));
    }

    @Test
    void keysOfOtherServicesAreIgnoredWithoutAFailure() throws Exception {
        String own = Env.subject("own");
        String foreign = Env.subject("foreign");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ConfigRegistry registry = new ConfigRegistry(List.of(), metrics);
        ConfigValue value = registry.register(own, 1, 1, 10);
        ConfigListener listener = listen(registry, metrics);

        publish(foreign, 5, 2);
        publish(own, 7, 2);

        assertTrue(Env.await(WAIT, () -> value.get() == 7));
        assertTrue(count(metrics, "config_ignored_total", "key", foreign) >= 1);
        assertEquals(0, count(metrics, "config_listener_failures_total"));
        assertTrue(listener.isRunning());
    }
}
