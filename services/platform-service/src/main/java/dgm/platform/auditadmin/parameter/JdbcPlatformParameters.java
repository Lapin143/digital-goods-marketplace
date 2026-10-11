package dgm.platform.auditadmin.parameter;

import dgm.kit.boot.ModuleDatabases;
import dgm.platform.auditadmin.api.PlatformParameters;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Параметры платформы из PostgreSQL под ролью {@code app_audit_admin}. Таблица маленькая (десяток строк), поэтому читается целиком и
 * держится в памяти {@link #REFRESH}: лимиты кодов не делают запроса к базе на каждое обращение, а изменение администратором доходит
 * за секунды. Если база не ответила, действует последний прочитанный снимок, а если его нет, значение по умолчанию вызывающего.
 */
@Component
public final class JdbcPlatformParameters implements PlatformParameters {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcPlatformParameters.class);
    static final String MODULE = "audit-admin";
    static final Duration REFRESH = Duration.ofSeconds(10);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Object lock = new Object();
    private volatile Map<String, Integer> snapshot = Map.of();
    private volatile Instant loadedAt = Instant.MIN;

    public JdbcPlatformParameters(ModuleDatabases databases, Clock clock) {
        this.jdbc = databases.jdbc(MODULE);
        this.clock = clock;
    }

    @Override
    public int value(String key, int fallback) {
        Instant now = clock.instant();
        if (now.isAfter(loadedAt.plus(REFRESH))) {
            reload(now);
        }
        Integer value = snapshot.get(key);
        return value == null ? fallback : value;
    }

    private void reload(Instant now) {
        synchronized (lock) {
            if (!now.isAfter(loadedAt.plus(REFRESH))) {
                return;   // пока ждали замок, снимок обновил другой запрос
            }
            try {
                Map<String, Integer> fresh = new HashMap<>();
                jdbc.query("select key, value from audit_admin.platform_parameter", rs -> {
                    fresh.put(rs.getString("key"), rs.getInt("value"));
                });
                snapshot = Map.copyOf(fresh);
                loadedAt = now;
            } catch (DataAccessException e) {
                // Следующая попытка через REFRESH: до тех пор действует прежний снимок или значения по умолчанию
                loadedAt = now;
                LOG.warn("Параметры платформы не прочитаны, действует прежний снимок ({} значений): {}", snapshot.size(),
                        e.getClass().getSimpleName());
            }
        }
    }
}
