package dgm.platform.auditadmin.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.platform.auditadmin.domain.PlatformParameter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Параметры платформы в PostgreSQL под ролью {@code app_audit_admin}. Версию и время правки ставит триггер таблицы, поэтому значение
 * пишется одним {@code UPDATE ... RETURNING}. Ограничения таблицы (границы строки, INV-07 в триггере) остаются запасным контролем:
 * основную проверку делает сервис под блокировкой пары строк.
 */
@Component
public final class JdbcParameterRepository implements ParameterRepository {

    static final String MODULE = "audit-admin";
    private static final String COLUMNS = "key, description, unit, value, default_value, min_value, max_value, version, updated_at, updated_by";
    /** Ключ консультативной блокировки переиздания: у всех экземпляров один, действует в пределах базы платформы. */
    static final long REISSUE_LOCK_KEY = 0x44474D434F4E4647L;

    private final JdbcTemplate jdbc;

    public JdbcParameterRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public List<PlatformParameter> findAll() {
        return jdbc.query("select " + COLUMNS + " from audit_admin.platform_parameter order by key", (rs, row) -> map(rs));
    }

    @Override
    public List<PlatformParameter> lock(List<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        List<String> ordered = new ArrayList<>(new TreeSet<>(keys));
        String marks = String.join(", ", Collections.nCopies(ordered.size(), "?"));
        // Порядок блокировки задаёт сортировка запроса, она же порядок ключей: у пары «сессия, резерв» он всегда один
        return jdbc.query("select " + COLUMNS + " from audit_admin.platform_parameter where key in (" + marks + ") order by key for update",
                (rs, row) -> map(rs), ordered.toArray());
    }

    @Override
    public PlatformParameter updateValue(String key, long value, UUID updatedBy) {
        return jdbc.queryForObject("update audit_admin.platform_parameter set value = ?, updated_by = ? where key = ? returning " + COLUMNS,
                (rs, row) -> map(rs), value, updatedBy, key);
    }

    @Override
    public boolean tryReissueLock() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, REISSUE_LOCK_KEY));
    }

    private static PlatformParameter map(ResultSet rs) throws SQLException {
        return new PlatformParameter(rs.getString("key"), rs.getString("description"), rs.getString("unit"), rs.getLong("value"),
                rs.getLong("default_value"), rs.getLong("min_value"), rs.getLong("max_value"), rs.getInt("version"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getObject("updated_by", UUID.class));
    }
}
