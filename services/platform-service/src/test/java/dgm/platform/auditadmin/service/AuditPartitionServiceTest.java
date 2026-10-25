package dgm.platform.auditadmin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dgm.kit.time.ManualClock;
import dgm.platform.auditadmin.testing.InMemoryAuditLog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Секции журнала (Р-164, правило 15): первое число текущего месяца по UTC, три месяца вперёд, строки страховочной секции в метрике. */
@Tag("TC-812")
class AuditPartitionServiceTest {

    private final InMemoryAuditLog log = new InMemoryAuditLog();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    private AuditPartitionService at(String instant) {
        return new AuditPartitionService(log, new ManualClock(Instant.parse(instant)), metrics);
    }

    private double defaultRows() {
        return metrics.get(AuditPartitionService.GAUGE).gauge().value();
    }

    @Test
    void partitionsStartAtTheFirstDayOfTheCurrentMonthAndCoverThreeMonthsAhead() {
        assertEquals(4, at("2026-10-25T12:00:00Z").run());
        assertEquals(LocalDate.of(2026, 10, 1), log.partitionsFrom);
        assertEquals(AuditPartitionService.MONTHS, log.partitionMonths);
        assertEquals(4, AuditPartitionService.MONTHS, "текущий месяц и три вперёд");
    }

    @Test
    void monthBoundaryIsTakenInUtc() {
        at("2026-12-31T23:59:59Z").run();
        assertEquals(LocalDate.of(2026, 12, 1), log.partitionsFrom);
        at("2027-01-01T00:00:00Z").run();
        assertEquals(LocalDate.of(2027, 1, 1), log.partitionsFrom);
    }

    @Test
    void anotherInstanceHoldingTheLockGivesZeroAndThePassIsStillCounted() {
        log.partitionResult = 0;
        assertEquals(0, at("2026-10-25T12:00:00Z").run());
    }

    @Test
    void rowsInTheSafetyPartitionAreVisibleInTheGauge() {
        AuditPartitionService service = at("2026-10-25T12:00:00Z");
        service.run();
        assertEquals(0.0, defaultRows());
        log.defaultRows = 3;
        service.run();
        assertEquals(3.0, defaultRows());
        log.defaultRows = 0;
        service.run();
        assertEquals(0.0, defaultRows(), "после переноса строк значение возвращается к нулю");
    }
}
