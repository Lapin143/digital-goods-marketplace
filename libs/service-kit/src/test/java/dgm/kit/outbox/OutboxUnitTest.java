package dgm.kit.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.id.Uuids;
import dgm.kit.json.Json;
import dgm.kit.time.ManualClock;
import dgm.kit.trace.TraceContext;
import dgm.kit.trace.TraceContexts;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OutboxUnitTest {

    private static final UUID EVENT_ID = UUID.fromString("0199c0de-0000-7000-8000-000000000001");
    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    private static final String CORRELATION = "6b3c0a1e-1111-4111-8111-111111111111";

    private static OutboxRow row(String payload, String headers) {
        return new OutboxRow(7, EVENT_ID, "Order", "ord-1", "order.paid", "order.events", payload, headers,
                Instant.parse("2026-10-04T10:00:00.123456Z"), 0);
    }

    private static final String HEADERS = "{\"schemaversion\": 1, \"traceparent\": \"" + TRACEPARENT + "\", \"correlationid\": \"" + CORRELATION + "\"}";

    @Test
    void envelopeHasAllCloudEventsAttributesInTheDocumentedOrder() throws Exception {
        OutgoingEvent e = new EnvelopeBuilder("order-service").build(row("{\"orderId\": \"ord-1\", \"total\": 1990}", HEADERS));
        Map<String, Object> doc = Json.parseObject(e.value());
        assertEquals(List.of("specversion", "id", "source", "type", "time", "subject", "datacontenttype", "schemaversion", "aggregatetype",
                "traceparent", "correlationid", "data"), new ArrayList<>(doc.keySet()));
        assertEquals("1.0", doc.get("specversion"));
        assertEquals(EVENT_ID.toString(), doc.get("id"));
        assertEquals("order-service", doc.get("source"));
        assertEquals("order.paid", doc.get("type"));
        assertEquals("2026-10-04T10:00:00.123456Z", doc.get("time"));
        assertEquals("ord-1", doc.get("subject"));
        assertEquals("application/json", doc.get("datacontenttype"));
        assertEquals(1, doc.get("schemaversion"));
        assertEquals("Order", doc.get("aggregatetype"));
        assertEquals(TRACEPARENT, doc.get("traceparent"));
        assertEquals(CORRELATION, doc.get("correlationid"));
        assertEquals(Map.of("orderId", "ord-1", "total", 1990), doc.get("data"));
    }

    @Test
    void kafkaRecordKeyAndHeaderValuesComeFromTheRow() throws Exception {
        OutgoingEvent e = new EnvelopeBuilder("order-service").build(row("{}", HEADERS));
        assertEquals("order.events", e.topic());
        assertEquals("ord-1", e.key());
        assertEquals("order.paid", e.type());
        assertEquals(TRACEPARENT, e.traceparent());
        assertEquals(EVENT_ID, e.eventId());
    }

    @Test
    void payloadTextIsSplicedWithoutReformatting() throws Exception {
        OutgoingEvent e = new EnvelopeBuilder("order-service").build(row("{\"price\": 19.90, \"big\": 12345678901234567890}", HEADERS));
        assertTrue(e.value().endsWith(",\"data\":{\"price\": 19.90, \"big\": 12345678901234567890}}"), e.value());
    }

    @Test
    void headersWithoutRequiredFieldsAreUnrecoverable() {
        for (String broken : new String[] {"{}", "{\"schemaversion\": 0, \"traceparent\": \"x\", \"correlationid\": \"y\"}",
                "{\"schemaversion\": 1, \"correlationid\": \"y\"}", "not json"}) {
            PublishException e = assertThrows(PublishException.class, () -> new EnvelopeBuilder("order-service").build(row("{}", broken)));
            assertTrue(e.unrecoverable(), broken);
        }
    }

    @Test
    void messageOver64KbIsUnrecoverable() {
        String big = "{\"blob\": \"" + "x".repeat(EnvelopeBuilder.MAX_MESSAGE_BYTES) + "\"}";
        PublishException e = assertThrows(PublishException.class, () -> new EnvelopeBuilder("order-service").build(row(big, HEADERS)));
        assertTrue(e.unrecoverable());
    }

    @Test
    void messageJustUnderLimitPasses() throws Exception {
        int overhead = new EnvelopeBuilder("order-service").build(row("{\"b\": \"\"}", HEADERS)).value().length();
        String filler = "x".repeat(EnvelopeBuilder.MAX_MESSAGE_BYTES - overhead - 1);
        OutgoingEvent e = new EnvelopeBuilder("order-service").build(row("{\"b\": \"" + filler + "\"}", HEADERS));
        assertTrue(e.value().length() <= EnvelopeBuilder.MAX_MESSAGE_BYTES);
    }

    @Test
    void sourceIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new EnvelopeBuilder(" "));
    }

    @Test
    void backoffGrowsOneTwoFourUpToThirtySeconds() {
        OutboxSettings s = OutboxSettings.defaults();
        List<Long> seconds = new ArrayList<>();
        for (int failure = 1; failure <= 8; failure++) {
            seconds.add(s.backoff(failure).toSeconds());
        }
        assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L), seconds);
        assertEquals(Duration.ofMillis(200), s.pollInterval());
        assertEquals(100, s.batchSize());
        assertEquals(10, s.maxAttempts());
    }

    @Test
    void settingsRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxSettings(Duration.ofMillis(1), 0, 10, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new OutboxSettings(Duration.ofMillis(1), 1, 0, Duration.ofSeconds(1), 1));
    }

    /** JdbcTemplate, который только запоминает вызов: база для проверки состава записи не нужна. */
    private static final class RecordingJdbc extends JdbcTemplate {
        final List<Object[]> calls = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            calls.add(args);
            return 1;
        }
    }

    @Test
    void writerRefusesToWriteOutsideTransaction() {
        OutboxWriter writer = new OutboxWriter(new RecordingJdbc(), new ManualClock(Instant.parse("2026-10-04T10:00:00Z")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> writer.write("order.events", "Order", "ord-1", "order.paid", 1, Map.of("orderId", "ord-1")));
        assertTrue(e.getMessage().contains("транзакции"));
    }

    @Test
    void writerStoresEventWithTraceHeadersInsideTransaction() {
        RecordingJdbc jdbc = new RecordingJdbc();
        ManualClock clock = new ManualClock(Instant.parse("2026-10-04T10:00:00Z"));
        OutboxWriter writer = new OutboxWriter(jdbc, clock);
        TraceContext context = TraceContext.incoming(TRACEPARENT, CORRELATION);
        TraceContexts.Scope scope = TraceContexts.open(context);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            UUID id = writer.write("order.events", "Order", "ord-1", "order.paid", 2, Map.of("orderId", "ord-1"));
            assertEquals(7, id.version());
            assertEquals(clock.millis(), Uuids.timestampMillis(id));
            Object[] args = jdbc.calls.get(0);
            assertEquals(id, args[0]);
            assertEquals("Order", args[1]);
            assertEquals("ord-1", args[2]);
            assertEquals("order.paid", args[3]);
            assertEquals("order.events", args[4]);
            assertEquals("{\"orderId\":\"ord-1\"}", args[5]);
            Map<String, Object> headers = Json.parseObject((String) args[6]);
            assertEquals(2, headers.get("schemaversion"));
            assertEquals(CORRELATION, headers.get("correlationid"));
            String traceparent = (String) headers.get("traceparent");
            assertTrue(traceparent.startsWith("00-0af7651916cd43dd8448eb211c80319c-"), "трасса запроса сохранена");
            assertFalse(traceparent.equals(context.traceparent()), "у события свой отрезок");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            scope.close();
        }
    }

    @Test
    void writerValidatesArguments() {
        OutboxWriter writer = new OutboxWriter(new RecordingJdbc(), new ManualClock(Instant.parse("2026-10-04T10:00:00Z")));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalArgumentException.class, () -> writer.write("", "Order", "1", "order.paid", 1, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> writer.write("t", "Order", " ", "order.paid", 1, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> writer.write("t", "Order", "1", "order.paid", 0, Map.of()));
            assertThrows(NullPointerException.class, () -> writer.write("t", "Order", "1", "order.paid", 1, null));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
