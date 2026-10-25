package dgm.platform.auditadmin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.id.Uuids;
import dgm.kit.time.ManualClock;
import dgm.platform.auditadmin.testing.AuditData;
import dgm.platform.auditadmin.testing.InMemoryAuditLog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Приём записей журнала аудита (Р-164): запись ложится один раз, повторная доставка ничего не добавляет, открытое персональное значение
 * не повторяется, а уходит в DLQ сразу; счётчик {@code audit_records_total{result}} различает три исхода.
 */
@Tag("TC-813")
class AuditRecordServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:31:00Z");

    private InMemoryAuditLog log;
    private SimpleMeterRegistry metrics;
    private AuditRecordService service;

    @BeforeEach
    void setUp() {
        log = new InMemoryAuditLog();
        metrics = new SimpleMeterRegistry();
        service = new AuditRecordService(log, new ManualClock(NOW), metrics);
    }

    private double count(String result) {
        return metrics.counter(AuditRecordService.METRIC, "result", result).count();
    }

    @Test
    void recordIsInsertedOnceWithAGeneratedTimeBasedIdAndTheTimeOfRecording() {
        UUID eventId = UUID.randomUUID();
        HandleResult result = service.record(eventId, AuditData.staff());
        assertTrue(result.applied());
        assertEquals(1, log.rows.size());
        InMemoryAuditLog.Row row = log.rows.get(0);
        assertEquals(eventId, row.eventId());
        assertEquals(7, row.id().version(), "RecordID это UUID версии 7 (ADR-014)");
        assertEquals(NOW.toEpochMilli(), Uuids.timestampMillis(row.id()));
        assertEquals(NOW, row.recordedAt());
        assertEquals(AuditData.AT, row.entry().occurredAt(), "время действия берётся из события, а не из часов приёмника");
        assertEquals(1.0, count("inserted"));
    }

    @Test
    void redeliveryOfTheSameEventAddsNothing() {
        UUID eventId = UUID.randomUUID();
        service.record(eventId, AuditData.staff());
        HandleResult again = service.record(eventId, AuditData.staff());
        assertFalse(again.applied());
        assertEquals("duplicate", again.reason());
        assertEquals(1, log.rows.size());
        assertEquals(1.0, count("inserted"));
        assertEquals(1.0, count("duplicate"));
        assertEquals(0.0, count("rejected"));
    }

    @Test
    void differentEventsWithIdenticalContentAreDifferentRecords() {
        service.record(UUID.randomUUID(), AuditData.staff());
        service.record(UUID.randomUUID(), AuditData.staff());
        assertEquals(2, log.rows.size(), "два одинаковых действия подряд это два события, а не повтор");
    }

    @Test
    void openPersonalValueIsFatalAndNothingIsWritten() {
        Map<String, Object> data = AuditData.staff();
        data.put("changes", List.of(AuditData.change("email", true, null, "ivan.petrov@mail.example")));
        FatalEventException e = assertThrows(FatalEventException.class, () -> service.record(UUID.randomUUID(), data));
        assertFalse(e.getMessage().contains("ivan.petrov"), "в сообщении, которое уйдёт в DLQ и журнал приложения, значения нет");
        assertTrue(e.getMessage().contains("email"));
        assertEquals(0, log.rows.size());
        assertEquals(1.0, count("rejected"));
        assertEquals(0.0, count("inserted"));
    }

    @Test
    void shapeOutsideTheSchemaIsFatalWithoutAWrite() {
        Map<String, Object> data = AuditData.staff();
        data.put("actorRole", "superuser");
        assertThrows(FatalEventException.class, () -> service.record(UUID.randomUUID(), data));
        assertEquals(0, log.rows.size());
        assertEquals(1.0, count("rejected"));
    }

    @Test
    void tableConstraintViolationIsFatalAndItsDetailsStayOutOfTheMessage() {
        log.failure = new DataIntegrityViolationException("Failing row contains (ivan.petrov@mail.example)");
        FatalEventException e = assertThrows(FatalEventException.class, () -> service.record(UUID.randomUUID(), AuditData.staff()));
        assertFalse(e.getMessage().contains("ivan.petrov"), e.getMessage());
        assertEquals(1.0, count("rejected"));
    }

    @Test
    void unavailableDatabaseIsNotFatalSoTheConsumerRetries() {
        log.failure = new DataAccessResourceFailureException("connection refused");
        assertThrows(DataAccessResourceFailureException.class, () -> service.record(UUID.randomUUID(), AuditData.staff()));
        assertEquals(0.0, count("rejected"));
        assertEquals(0.0, count("inserted"));
    }
}
