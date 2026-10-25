package dgm.platform.auditadmin.testing;

import dgm.platform.auditadmin.domain.AuditRecordView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Записи журнала для проверки чтения. */
public final class AuditViews {

    public static final Instant BASE = Instant.parse("2026-10-25T12:00:00Z");

    private AuditViews() {
    }

    public static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", n));
    }

    /** Запись номер {@code n}: чем больше номер, тем позже она произошла. */
    public static AuditRecordView record(int n) {
        return new AuditRecordView(id(n), BASE.plusSeconds(n), id(900 + n % 3), "moderator", n % 2 == 0 ? "seller.approved" : "seller.rejected", "seller",
                "0199e0a0-0000-7000-8000-000000000101", List.of(new AuditRecordView.Change("status", false, "on_review", "approved")), "203.0.113.7", false,
                id(5000 + n));
    }

    /** Запись с заданным временем и идентификатором. */
    public static AuditRecordView at(int n, Instant occurredAt) {
        AuditRecordView base = record(n);
        return new AuditRecordView(base.recordId(), occurredAt, base.actorId(), base.actorRole(), base.action(), base.objectType(), base.objectId(),
                base.changes(), base.actorIp(), base.actorIpHashed(), base.correlationId());
    }
}
