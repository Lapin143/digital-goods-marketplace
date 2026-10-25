package dgm.platform.auditadmin.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.kit.json.Json;
import dgm.platform.auditadmin.domain.AuditEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Журнал аудита в PostgreSQL под ролью {@code app_audit_writer}: только {@code INSERT} и {@code SELECT}. Повторную доставку отсекает
 * уникальный индекс {@code (event_id, occurred_at)}: {@code ON CONFLICT DO NOTHING}, а не проверка перед вставкой, поэтому два экземпляра
 * потребителя с одним событием дают одну запись.
 */
@Component
public final class JdbcAuditLogRepository implements AuditLogRepository {

    static final String MODULE = "audit-writer";
    /** Ключ консультативной блокировки создания секций: у всех экземпляров один, действует в пределах базы платформы. */
    static final long PARTITION_LOCK_KEY = 0x44474D4155445054L;
    private static final int DEFAULT_PARTITION_CAP = 1000;

    private final JdbcTemplate jdbc;

    public JdbcAuditLogRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public boolean insert(UUID id, UUID eventId, AuditEntry entry, Instant recordedAt) {
        return jdbc.update("insert into audit_admin.audit_log (id, event_id, occurred_at, actor_id, actor_role, action, object_type, object_id, "
                + "changes, actor_ip, actor_ip_hashed, correlation_id, recorded_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) "
                + "on conflict (event_id, occurred_at) do nothing",
                id, eventId, utc(entry.occurredAt()), entry.actorId(), entry.actorRole(), entry.action(), entry.objectType(), entry.objectId(),
                Json.write(entry.changesAsMaps()), entry.actorIp(), entry.actorIpHashed(), entry.correlationId(), utc(recordedAt)) == 1;
    }

    @Override
    public int ensurePartitions(LocalDate first, int months) {
        // Один оператор: блокировка держится до его конца, поэтому второй экземпляр не получает ни одной строки
        Integer done = jdbc.queryForObject("select count(audit_admin.create_audit_log_partition((?::date + (m * interval '1 month'))::date)) "
                + "from generate_series(0, ? - 1) m where pg_try_advisory_xact_lock(?)", Integer.class, first, months, PARTITION_LOCK_KEY);
        return done == null ? 0 : done;
    }

    @Override
    public long defaultPartitionRows() {
        Long rows = jdbc.queryForObject("select count(*) from (select 1 from audit_admin.audit_log_default limit " + DEFAULT_PARTITION_CAP + ") t",
                Long.class);
        return rows == null ? 0 : rows;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
