package dgm.platform.auditadmin.testing;

import dgm.platform.auditadmin.domain.AuditEntry;
import dgm.platform.auditadmin.repository.AuditLogRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Журнал аудита в памяти для модульных тестов: уникальность {@code (event_id, occurred_at)} как у уникального индекса таблицы. */
public final class InMemoryAuditLog implements AuditLogRepository {

    /** Строка журнала, как её видит тест. */
    public record Row(UUID id, UUID eventId, AuditEntry entry, Instant recordedAt) {
    }

    public final List<Row> rows = new ArrayList<>();
    public LocalDate partitionsFrom;
    public int partitionMonths;
    public int partitionResult = -1;
    public long defaultRows;
    /** Если задана, вставка бросает её: так тест имитирует отказ базы. */
    public RuntimeException failure;

    @Override
    public synchronized boolean insert(UUID id, UUID eventId, AuditEntry entry, Instant recordedAt) {
        if (failure != null) {
            throw failure;
        }
        boolean exists = rows.stream().anyMatch(row -> row.eventId().equals(eventId) && row.entry().occurredAt().equals(entry.occurredAt()));
        if (exists) {
            return false;
        }
        rows.add(new Row(id, eventId, entry, recordedAt));
        return true;
    }

    @Override
    public int ensurePartitions(LocalDate first, int months) {
        partitionsFrom = first;
        partitionMonths = months;
        return partitionResult >= 0 ? partitionResult : months;
    }

    @Override
    public long defaultPartitionRows() {
        return defaultRows;
    }
}
