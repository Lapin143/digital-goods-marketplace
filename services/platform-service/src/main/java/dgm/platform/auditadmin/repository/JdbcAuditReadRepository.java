package dgm.platform.auditadmin.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.kit.json.Json;
import dgm.platform.auditadmin.domain.AuditFilter;
import dgm.platform.auditadmin.domain.AuditRecordView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Журнал аудита для чтения. Порядок {@code (occurred_at, id)} по убыванию совпадает с индексами журнала, а курсор это та же пара значений,
 * поэтому страница не сдвигается, когда в журнал ложатся новые записи. Значения условий идут параметрами запроса.
 */
@Component
public final class JdbcAuditReadRepository implements AuditReadRepository {

    static final String MODULE = "audit-admin";
    private static final String COLUMNS = "id, occurred_at, actor_id, actor_role, action, object_type, object_id, changes::text as changes, "
            + "actor_ip, actor_ip_hashed, correlation_id";

    private final JdbcTemplate jdbc;

    public JdbcAuditReadRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public List<AuditRecordView> list(AuditFilter f) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from audit_admin.audit_log where true");
        List<Object> args = new ArrayList<>();
        f.actorId().ifPresent(actor -> {
            sql.append(" and actor_id = ?");
            args.add(actor);
        });
        f.objectType().ifPresent(type -> {
            sql.append(" and object_type = ?");
            args.add(type);
        });
        f.objectId().ifPresent(id -> {
            sql.append(" and object_id = ?");
            args.add(id);
        });
        f.action().ifPresent(action -> {
            sql.append(" and action = ?");
            args.add(action);
        });
        f.occurredFrom().ifPresent(from -> {
            sql.append(" and occurred_at >= ?");
            args.add(OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
        });
        f.occurredTo().ifPresent(to -> {
            sql.append(" and occurred_at < ?");
            args.add(OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        });
        f.after().ifPresent(after -> {
            sql.append(" and (occurred_at, id) < (?, ?)");
            args.add(OffsetDateTime.ofInstant(after.occurredAt(), ZoneOffset.UTC));
            args.add(after.id());
        });
        sql.append(" order by occurred_at desc, id desc limit ?");
        args.add(f.limit() + 1);
        return jdbc.query(sql.toString(), (rs, row) -> map(rs), args.toArray());
    }

    private static AuditRecordView map(ResultSet rs) throws SQLException {
        String ip = rs.getString("actor_ip");
        return new AuditRecordView(rs.getObject("id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_role"), rs.getString("action"), rs.getString("object_type"),
                rs.getString("object_id"), changes(rs.getString("changes")), ip == null ? "" : ip, rs.getBoolean("actor_ip_hashed"),
                rs.getObject("correlation_id", UUID.class));
    }

    /** Числа и логические значения журнала становятся строками: в контракте {@code before} и {@code after} это строка или пусто (F4-29). */
    static List<AuditRecordView.Change> changes(String json) {
        List<AuditRecordView.Change> result = new ArrayList<>();
        if (json == null || !(Json.parse(json) instanceof List<?> items)) {
            return result;
        }
        for (Object item : items) {
            if (item instanceof Map<?, ?> change) {
                result.add(new AuditRecordView.Change(String.valueOf(change.get("field")), Boolean.TRUE.equals(change.get("personal")),
                        text(change.get("before")), text(change.get("after"))));
            }
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
