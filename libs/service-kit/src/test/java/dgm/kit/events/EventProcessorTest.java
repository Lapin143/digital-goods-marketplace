package dgm.kit.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.kit.trace.TraceContexts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** Порядок обработки записи (conventions.md, раздел 12) на подставных базе, брокере и паузах. */
class EventProcessorTest {

    private static final String SERVICE = "inventory-service";
    private static final String CORRELATION = "6b3c0a1e-1111-4111-8111-111111111111";

    // --- подставные части -------------------------------------------------------------------------------------------------------

    private static final class FakeProcessed implements ProcessedEvents {
        final Set<String> keys = new HashSet<>();

        @Override
        public boolean markProcessed(String consumer, UUID eventId) {
            return keys.add(consumer + "/" + eventId);
        }
    }

    /** Транзакция, которая при исключении возвращает таблицу обработанных в прежнее состояние, как откат в базе. */
    private static final class RollingBack implements TransactionOperations {
        private final FakeProcessed processed;

        RollingBack(FakeProcessed processed) {
            this.processed = processed;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            Set<String> snapshot = new HashSet<>(processed.keys);
            try {
                return action.doInTransaction(new SimpleTransactionStatus());
            } catch (RuntimeException e) {
                processed.keys.clear();
                processed.keys.addAll(snapshot);
                throw e;
            }
        }
    }

    private static final class RecordingDlq implements DlqSink {
        final List<ProducerRecord<String, String>> sent = new ArrayList<>();
        boolean failing;

        @Override
        public void send(ProducerRecord<String, String> record) throws EventProcessingException {
            if (failing) {
                throw new EventProcessingException("брокер недоступен", new IllegalStateException("down"));
            }
            sent.add(record);
        }
    }

    private static final class TestHandler implements EventHandler {
        final String name;
        final String topic;
        final Set<String> types;
        final int maxVersion;
        final List<EventEnvelope> seen = new ArrayList<>();
        Function<EventEnvelope, HandleResult> action = e -> HandleResult.done();

        TestHandler(String name, String topic, Set<String> types, int maxVersion) {
            this.name = name;
            this.topic = topic;
            this.types = types;
            this.maxVersion = maxVersion;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String topic() {
            return topic;
        }

        @Override
        public Set<String> types() {
            return types;
        }

        @Override
        public int maxSchemaVersion() {
            return maxVersion;
        }

        @Override
        public HandleResult handle(EventEnvelope event) {
            seen.add(event);
            return action.apply(event);
        }
    }

    private final FakeProcessed processed = new FakeProcessed();
    private final RecordingDlq dlq = new RecordingDlq();
    private final List<Duration> sleeps = new ArrayList<>();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-04T10:00:00Z"));
    private final TestHandler handler = new TestHandler("reservation-handler", "order.events", Set.of("order.paid"), 2);

    private EventProcessor processor(EventHandler... handlers) {
        return new EventProcessor(SERVICE, List.of(handlers), processed, new RollingBack(processed), dlq,
                EventProcessor.DEFAULT_RETRY_DELAYS, clock, sleeps::add, metrics);
    }

    private static String envelope(String id, String type, int version) {
        return EventEnvelopeTest.valid().replace(EventEnvelopeTest.ID, id).replace("order.paid", type)
                .replace("\"schemaversion\":1", "\"schemaversion\":" + version);
    }

    private static ConsumerRecord<String, String> record(String value) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("order.events", 1, 42L, "ord-1", value);
        return r;
    }

    private static ConsumerRecord<String, String> record(String value, String ceType) {
        ConsumerRecord<String, String> r = record(value);
        r.headers().add("ce_type", ceType.getBytes(StandardCharsets.UTF_8));
        r.headers().add("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01".getBytes(StandardCharsets.UTF_8));
        return r;
    }

    private static String header(ProducerRecord<String, String> r, String key) {
        var h = r.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private double count(String name, String... tags) {
        var counter = metrics.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    // --- обычный путь -----------------------------------------------------------------------------------------------------------

    @Test
    void validEventIsAppliedOnceAndNothingGoesToDlq() throws Exception {
        processor(handler).process(record(EventEnvelopeTest.valid(), "order.paid"));
        assertEquals(1, handler.seen.size());
        assertEquals("order.paid", handler.seen.get(0).type());
        assertTrue(dlq.sent.isEmpty());
        assertTrue(sleeps.isEmpty());
        assertEquals(1, count("events_processed_total", "result", "applied", "consumer", "reservation-handler"));
        assertTrue(processed.keys.contains("reservation-handler/" + EventEnvelopeTest.ID));
    }

    @Test
    void deliveredTwiceIsHandledOnce() throws Exception {
        EventProcessor p = processor(handler);
        p.process(record(EventEnvelopeTest.valid()));
        p.process(record(EventEnvelopeTest.valid()));
        assertEquals(1, handler.seen.size());
        assertEquals(1, count("events_processed_total", "result", "duplicate"));
        assertTrue(dlq.sent.isEmpty());
    }

    @Test
    void twoHandlersKeepSeparateProcessedRows() throws Exception {
        TestHandler other = new TestHandler("audit-handler", "order.events", Set.of("order.paid"), 1);
        processor(handler, other).process(record(EventEnvelopeTest.valid()));
        assertEquals(1, handler.seen.size());
        assertEquals(1, other.seen.size());
        assertEquals(2, processed.keys.size());
    }

    @Test
    void handlerThatIgnoresIsCountedByReason() throws Exception {
        handler.action = e -> HandleResult.ignored("Status Already Paid!");
        processor(handler).process(record(EventEnvelopeTest.valid()));
        assertEquals(1, count("events_ignored_total", "reason", "status_already_paid_"));
        assertTrue(dlq.sent.isEmpty());
    }

    @Test
    void handlerSeesTraceOfTheEvent() throws Exception {
        AtomicReference<String> correlation = new AtomicReference<>();
        AtomicReference<String> mdc = new AtomicReference<>();
        handler.action = e -> {
            correlation.set(TraceContexts.current().map(c -> c.correlationId()).orElse(null));
            mdc.set(org.slf4j.MDC.get(TraceContexts.MDC_CORRELATION_ID));
            return HandleResult.done();
        };
        processor(handler).process(record(EventEnvelopeTest.valid()));
        assertEquals(CORRELATION, correlation.get());
        assertEquals(CORRELATION, mdc.get());
        assertTrue(TraceContexts.current().isEmpty(), "контекст закрыт после обработки");
    }

    // --- повторы и DLQ ----------------------------------------------------------------------------------------------------------

    @Test
    void temporaryFailureIsRetriedAfterOneAndFiveSecondsAndSucceeds() throws Exception {
        int[] calls = {0};
        handler.action = e -> {
            if (++calls[0] <= 2) {
                throw new RetryableEventException("событие пришло раньше нужного статуса");
            }
            return HandleResult.done();
        };
        processor(handler).process(record(EventEnvelopeTest.valid()));
        assertEquals(3, calls[0]);
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(5)), sleeps);
        assertTrue(dlq.sent.isEmpty());
        assertEquals(2, count("events_retries_total"));
        assertEquals(1, processed.keys.size(), "после отката и успешного повтора строка одна");
    }

    @Test
    void exhaustedRetriesGoToDlqWithDocumentedHeaders() throws Exception {
        handler.action = e -> {
            throw new IllegalStateException("не удалось отправить на ivan@example.com");
        };
        processor(handler).process(record(EventEnvelopeTest.valid(), "order.paid"));

        assertEquals(4, handler.seen.size(), "первая попытка и три повтора");
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(25)), sleeps);
        assertEquals(1, dlq.sent.size());
        ProducerRecord<String, String> out = dlq.sent.get(0);
        assertEquals("order.events.dlq", out.topic());
        assertEquals(1, out.partition(), "партиция как у исходной записи");
        assertEquals("ord-1", out.key());
        assertEquals(EventEnvelopeTest.valid(), out.value(), "ключ и значение без изменений");
        assertEquals("order.events", header(out, "dlq-original-topic"));
        assertEquals("1", header(out, "dlq-original-partition"));
        assertEquals("42", header(out, "dlq-original-offset"));
        assertEquals("reservation-handler", header(out, "dlq-consumer"));
        assertEquals("java.lang.IllegalStateException", header(out, "dlq-error-class"));
        assertEquals("4", header(out, "dlq-attempts"));
        assertEquals("2026-10-04T10:00:00Z", header(out, "dlq-failed-at"));
        assertFalse(header(out, "dlq-error-message").contains("ivan@example.com"), "адрес в тексте ошибки замаскирован");
        assertEquals("order.paid", header(out, "ce_type"), "исходные заголовки сохранены");
        assertTrue(header(out, "traceparent").startsWith("00-0af7"));
        assertTrue(processed.keys.isEmpty(), "строки processed_event откатились вместе с обработчиком");
        assertEquals(1, count("events_dlq_total", "consumer", "reservation-handler"));
    }

    @Test
    void fatalErrorGoesToDlqAtOnceWithoutRetries() throws Exception {
        handler.action = e -> {
            throw new FatalEventException("нет обязательного поля orderId");
        };
        processor(handler).process(record(EventEnvelopeTest.valid()));
        assertEquals(1, handler.seen.size());
        assertTrue(sleeps.isEmpty());
        assertEquals("1", header(dlq.sent.get(0), "dlq-attempts"));
    }

    @Test
    void checkedExceptionOfHandlerIsTreatedAsTemporary() throws Exception {
        EventHandler throwing = new EventHandler() {
            @Override
            public String name() {
                return "checked";
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
            public HandleResult handle(EventEnvelope event) throws Exception {
                throw new java.io.IOException("диск");
            }
        };
        processor(throwing).process(record(EventEnvelopeTest.valid()));
        assertEquals(3, sleeps.size());
        assertEquals("dgm.kit.events.RetryableEventException", header(dlq.sent.get(0), "dlq-error-class"));
    }

    @Test
    void invalidEnvelopeGoesToDlqImmediatelyUnderServiceName() throws Exception {
        for (String bad : new String[] {"not json", "{}", EventEnvelopeTest.valid().replace("1.0", "2.0"),
                EventEnvelopeTest.valid().replace("\"subject\":\"ord-1\"", "\"subject\":\"ord-2\"")}) {
            dlq.sent.clear();
            processor(handler).process(record(bad));
            assertEquals(1, dlq.sent.size(), bad);
            assertEquals(SERVICE, header(dlq.sent.get(0), "dlq-consumer"));
            assertEquals("1", header(dlq.sent.get(0), "dlq-attempts"));
        }
        assertTrue(handler.seen.isEmpty());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void failureToWriteDlqLeavesRecordUncommittable() {
        dlq.failing = true;
        handler.action = e -> {
            throw new FatalEventException("плохое событие");
        };
        assertThrows(EventProcessingException.class, () -> processor(handler).process(record(EventEnvelopeTest.valid())));
    }

    @Test
    void interruptedWhileWaitingForRetryStopsProcessing() {
        handler.action = e -> {
            throw new RetryableEventException("ещё рано");
        };
        EventProcessor p = new EventProcessor(SERVICE, List.of(handler), processed, new RollingBack(processed), dlq,
                EventProcessor.DEFAULT_RETRY_DELAYS, clock, d -> {
                    throw new InterruptedException();
                }, metrics);
        assertThrows(EventProcessingException.class, () -> p.process(record(EventEnvelopeTest.valid())));
        assertTrue(Thread.interrupted(), "флаг прерывания сохранён");
        assertTrue(dlq.sent.isEmpty());
    }

    // --- пропуск ----------------------------------------------------------------------------------------------------------------

    @Test
    void unknownTypeByHeaderIsSkippedWithoutParsingBody() throws Exception {
        processor(handler).process(record("not even json", "order.cancelled"));
        assertTrue(handler.seen.isEmpty());
        assertTrue(dlq.sent.isEmpty());
        assertEquals(1, count("events_skipped_total", "reason", "unknown-type"));
    }

    @Test
    void unknownTypeInBodyIsSkipped() throws Exception {
        processor(handler).process(record(envelope(EventEnvelopeTest.ID, "order.cancelled", 1)));
        assertTrue(handler.seen.isEmpty());
        assertEquals(1, count("events_skipped_total", "reason", "unknown-type"));
    }

    @Test
    void newerSchemaVersionIsSkippedAndNotRecorded() throws Exception {
        processor(handler).process(record(envelope(EventEnvelopeTest.ID, "order.paid", 3)));
        assertTrue(handler.seen.isEmpty());
        assertTrue(processed.keys.isEmpty());
        assertEquals(1, count("events_skipped_total", "reason", "unknown-version"));
        processor(handler).process(record(envelope("0199c0de-0000-7000-8000-000000000002", "order.paid", 2)));
        assertEquals(1, handler.seen.size(), "версия не выше допустимой принимается");
    }

    @Test
    void topicWithoutHandlersIsSkipped() throws Exception {
        TestHandler other = new TestHandler("x", "payment.events", Set.of("payment.confirmed"), 1);
        processor(other).process(record(EventEnvelopeTest.valid()));
        assertTrue(other.seen.isEmpty());
        assertEquals(1, count("events_skipped_total", "reason", "unknown-topic"));
    }

    @Test
    void oneFailingHandlerDoesNotBlockTheOther() throws Exception {
        TestHandler broken = new TestHandler("broken", "order.events", Set.of("order.paid"), 1);
        broken.action = e -> {
            throw new FatalEventException("нет поля");
        };
        processor(broken, handler).process(record(EventEnvelopeTest.valid()));
        assertEquals(1, handler.seen.size());
        assertEquals(1, dlq.sent.size());
        assertEquals("broken", header(dlq.sent.get(0), "dlq-consumer"));
    }

    @Test
    void topicsAreDistinctAndSorted() {
        TestHandler a = new TestHandler("a", "payment.events", Set.of("p"), 1);
        TestHandler b = new TestHandler("b", "order.events", Set.of("o"), 1);
        TestHandler c = new TestHandler("c", "order.events", Set.of("o2"), 1);
        assertEquals(List.of("order.events", "payment.events"), processor(a, b, c).topics());
    }

    @Test
    void metricLabelsAreSanitized() {
        assertEquals("unspecified", EventProcessor.label(null));
        assertEquals("unspecified", EventProcessor.label(" "));
        assertEquals("a_b-c_1", EventProcessor.label("A b-c_1"));
        assertEquals(40, EventProcessor.label("x".repeat(100)).length());
    }
}
