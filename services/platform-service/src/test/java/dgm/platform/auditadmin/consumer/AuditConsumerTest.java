package dgm.platform.auditadmin.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.time.ManualClock;
import dgm.platform.auditadmin.service.AuditRecordService;
import dgm.platform.auditadmin.testing.AuditData;
import dgm.platform.auditadmin.testing.InMemoryAuditLog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Обработчик {@code audit-consumer}: что объявлено в контракте событий (x-consumer) и как событие доходит до журнала. */
@Tag("TC-813")
class AuditConsumerTest {

    private InMemoryAuditLog log;
    private AuditConsumer consumer;

    @BeforeEach
    void setUp() {
        log = new InMemoryAuditLog();
        consumer = new AuditConsumer(new AuditRecordService(log, new ManualClock(Instant.parse("2026-10-25T12:31:00Z")), new SimpleMeterRegistry()));
    }

    private static EventEnvelope event(UUID id, Map<String, Object> data) {
        return new EventEnvelope(id, "catalog-service", "audit.recorded", AuditData.AT, UUID.randomUUID().toString(), 1, "AuditRecord",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90222-01", UUID.randomUUID().toString(), data);
    }

    @Test
    void declarationMatchesTheEventContract() {
        assertEquals("audit-consumer", consumer.name(), "x-consumer.handler");
        assertEquals("audit.events", consumer.topic());
        assertEquals(Set.of("audit.recorded"), consumer.types());
        assertEquals(1, consumer.maxSchemaVersion());
        assertEquals("audit-writer", consumer.module(), "записи идут под ролью app_audit_writer");
    }

    @Test
    void eventBecomesARecordWithTheEventIdAsTheDeduplicationKey() {
        UUID eventId = UUID.randomUUID();
        HandleResult result = consumer.handle(event(eventId, AuditData.staff()));
        assertTrue(result.applied());
        assertEquals(List.of(eventId), log.rows.stream().map(InMemoryAuditLog.Row::eventId).toList());
        HandleResult again = consumer.handle(event(eventId, AuditData.staff()));
        assertFalse(again.applied());
        assertEquals(1, log.rows.size());
    }

    @Test
    void openPersonalValueGoesToTheDlqWithoutRetries() {
        Map<String, Object> data = AuditData.staff();
        data.put("changes", List.of(AuditData.change("phone", true, null, "+79991234567")));
        assertThrows(FatalEventException.class, () -> consumer.handle(event(UUID.randomUUID(), data)));
        assertEquals(0, log.rows.size());
    }
}
