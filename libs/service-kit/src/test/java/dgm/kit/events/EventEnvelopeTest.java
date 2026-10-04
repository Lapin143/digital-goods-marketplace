package dgm.kit.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    static final String ID = "0199c0de-0000-7000-8000-000000000001";

    static String valid() {
        return """
                {"specversion":"1.0","id":"%s","source":"order-service","type":"order.paid","time":"2026-10-04T10:00:00.123Z",
                 "subject":"ord-1","datacontenttype":"application/json","schemaversion":1,"aggregatetype":"Order",
                 "traceparent":"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                 "correlationid":"6b3c0a1e-1111-4111-8111-111111111111","data":{"orderId":"ord-1","total":1990}}
                """.formatted(ID);
    }

    @Test
    void parsesValidEnvelope() throws Exception {
        EventEnvelope e = EventEnvelope.parse(valid());
        assertEquals(ID, e.id().toString());
        assertEquals("order-service", e.source());
        assertEquals("order.paid", e.type());
        assertEquals(Instant.parse("2026-10-04T10:00:00.123Z"), e.time());
        assertEquals("ord-1", e.subject());
        assertEquals(1, e.schemaVersion());
        assertEquals("Order", e.aggregateType());
        assertEquals("6b3c0a1e-1111-4111-8111-111111111111", e.correlationId());
        assertEquals(Map.of("orderId", "ord-1", "total", 1990), e.data());
    }

    @Test
    void acceptsTimeWithOffsetAndNullsInData() throws Exception {
        String text = valid().replace("2026-10-04T10:00:00.123Z", "2026-10-04T13:00:00+03:00").replace("{\"orderId\":\"ord-1\",\"total\":1990}", "{\"note\":null}");
        EventEnvelope e = EventEnvelope.parse(text);
        assertEquals(Instant.parse("2026-10-04T10:00:00Z"), e.time());
        assertEquals(1, e.data().size());
    }

    private static void rejects(String text) {
        assertThrows(InvalidEventException.class, () -> EventEnvelope.parse(text), text);
    }

    @Test
    void rejectsBrokenDocuments() {
        rejects(null);
        rejects("");
        rejects("not json");
        rejects("[]");
        rejects("{}");
    }

    @Test
    void rejectsEachViolatedAttribute() {
        rejects(valid().replace("\"specversion\":\"1.0\"", "\"specversion\":\"0.3\""));
        rejects(valid().replace(ID, "not-a-uuid"));
        rejects(valid().replace("\"source\":\"order-service\",", ""));
        rejects(valid().replace("order.paid", "OrderPaid"));
        rejects(valid().replace("2026-10-04T10:00:00.123Z", "yesterday"));
        rejects(valid().replace("\"subject\":\"ord-1\",", ""));
        rejects(valid().replace("application/json", "text/plain"));
        rejects(valid().replace("\"schemaversion\":1", "\"schemaversion\":0"));
        rejects(valid().replace("\"schemaversion\":1", "\"schemaversion\":\"1\""));
        rejects(valid().replace("\"schemaversion\":1", "\"schemaversion\":1.5"));
        rejects(valid().replace("\"aggregatetype\":\"Order\",", ""));
        rejects(valid().replace("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "garbage"));
        rejects(valid().replace("6b3c0a1e-1111-4111-8111-111111111111", "garbage"));
        rejects(valid().replace("\"data\":{\"orderId\":\"ord-1\",\"total\":1990}", "\"data\":[1]"));
        rejects(valid().replace(",\"data\":{\"orderId\":\"ord-1\",\"total\":1990}", ""));
    }

    @Test
    void rejectsMessageOver64Kb() {
        rejects(valid().replace("ord-1\",\"total\"", "x".repeat(EventEnvelope.MAX_MESSAGE_BYTES) + "\",\"total\""));
    }
}
