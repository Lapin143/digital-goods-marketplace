package dgm.kit.boot;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dgm.kit.tls.TlsMaterial;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Подключения сервиса к своей базе: у каждого модуля своя рабочая роль, свой пул и своя транзакция (правило модульности 2:
 * модуль видит только свою схему). Миграции применяются до открытия пулов под ролью-мигратором, который работает только при старте.
 * Соединение всегда по TLS с проверкой сертификата и имени узла (ADR-022), пароли роли читаются из секретов.
 */
public final class ModuleDatabases implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleDatabases.class);

    private final KitProperties.Db settings;
    private final SecretsDirectory secrets;
    private final TlsMaterial tls;
    private final String service;
    private final Map<String, HikariDataSource> pools = new LinkedHashMap<>();
    private final Map<String, JdbcTemplate> jdbc = new LinkedHashMap<>();
    private final Map<String, TransactionOperations> transactions = new LinkedHashMap<>();
    private final List<HikariDataSource> dedicated = new ArrayList<>();

    private ModuleDatabases(KitProperties.Db settings, SecretsDirectory secrets, TlsMaterial tls, String service) {
        this.settings = settings;
        this.secrets = secrets;
        this.tls = tls;
        this.service = service;
    }

    /** Применяет миграции (если включено) и открывает пулы модулей. Пулы соединений лениво: база может подняться чуть позже. */
    public static ModuleDatabases open(KitProperties.Db settings, SecretsDirectory secrets, TlsMaterial tls, String service) {
        ModuleDatabases databases = new ModuleDatabases(settings, secrets, tls, service);
        if (settings.migrate()) {
            databases.migrate();
        }
        settings.modules().forEach((name, module) -> {
            HikariDataSource pool = databases.pool("module-" + name, module.role(), module.poolSize());
            databases.pools.put(name, pool);
            databases.jdbc.put(name, new JdbcTemplate(pool));
            databases.transactions.put(name, new TransactionTemplate(new DataSourceTransactionManager(pool)));
        });
        return databases;
    }

    private void migrate() {
        if (settings.migratorRole() == null) {
            throw new IllegalStateException("Не задана роль-мигратор dgm.db.migrator-role");
        }
        String url = tls.jdbcUrl(settings.host(), settings.port(), settings.database());
        Flyway flyway = Flyway.configure()
                .dataSource(url, settings.migratorRole(), secrets.read("db_" + settings.migratorRole()).reveal())
                .locations(settings.locations().toArray(new String[0]))
                .cleanDisabled(true)
                // Тестовые данные (профиль testdata) остаются в базе, если профиль выключили: отсутствие их файлов не ошибка,
                // а изменённые или новые миграции проверяются как обычно
                .ignoreMigrationPatterns("*:missing")
                .connectRetries(10)
                .load();
        int applied = flyway.migrate().migrationsExecuted;
        LOG.info("Миграции базы {} применены под ролью {}: выполнено {}", settings.database(), settings.migratorRole(), applied);
    }

    private HikariDataSource pool(String poolName, String role, int size) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(service + "-" + poolName);
        config.setJdbcUrl(tls.jdbcUrl(settings.host(), settings.port(), settings.database()));
        config.setUsername(role);
        config.setPassword(secrets.read("db_" + role).reveal());
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setInitializationFailTimeout(-1);
        config.setMaxLifetime(30 * 60_000L);
        config.addDataSourceProperty("ApplicationName", service);
        HikariDataSource pool = new HikariDataSource(config);
        return pool;
    }

    /** Подключение модуля для запросов. */
    public DataSource dataSource(String module) {
        return require(pools, module);
    }

    public JdbcTemplate jdbc(String module) {
        return require(jdbc, module);
    }

    /** Транзакция модуля: изменение данных и запись в Outbox идут в ней одной (ADR-005). */
    public TransactionOperations transactions(String module) {
        return require(transactions, module);
    }

    public List<String> modules() {
        return List.copyOf(pools.keySet());
    }

    /** Отдельный пул под ролью модуля (одно соединение публикатора Outbox, чтобы не отнимать его у запросов). */
    public DataSource dedicated(String module, String name, int size) {
        KitProperties.DbModule settingsOfModule = settings.modules().get(module);
        if (settingsOfModule == null) {
            throw new NoSuchElementException("Нет модуля " + module);
        }
        HikariDataSource pool = pool(name, settingsOfModule.role(), size);
        dedicated.add(pool);
        return pool;
    }

    /** Все пулы отвечают на простой запрос: основа проверки готовности. */
    public boolean healthy() {
        for (HikariDataSource pool : pools.values()) {
            try (Connection c = pool.getConnection(); var st = c.createStatement()) {
                st.execute("select 1");
            } catch (SQLException e) {
                LOG.warn("База недоступна для пула {}: {}", pool.getPoolName(), e.getMessage());
                return false;
            }
        }
        return true;
    }

    private static <T> T require(Map<String, T> map, String module) {
        T value = map.get(module);
        if (value == null) {
            throw new NoSuchElementException("Нет подключения модуля " + module + ", есть: " + map.keySet());
        }
        return value;
    }

    @Override
    public void close() {
        dedicated.forEach(HikariDataSource::close);
        pools.values().forEach(HikariDataSource::close);
    }
}
