package dgm.kit.boot;

import dgm.kit.events.JdbcProcessedEvents;
import dgm.kit.events.ProcessedEvents;
import dgm.kit.idempotency.IdempotencyGuard;
import dgm.kit.idempotency.JdbcIdempotencyStore;
import dgm.kit.outbox.OutboxWriter;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Всё, что сервис получает от своего модуля базы, в одном месте: подключение, транзакция, запись в Outbox, учёт обработанных событий и
 * охранник идемпотентности. У каждого модуля свои роль, пул и транзакция (правило модульности 2), поэтому запись в Outbox и транзакция
 * бизнес-изменения берутся у одного и того же модуля:
 *
 * <pre>{@code
 * TransactionOperations tx = modules.transactions("support");
 * OutboxWriter outbox = modules.outbox("support");
 * tx.execute(status -> { repository.save(ticket); outbox.write(...); return null; });
 * }</pre>
 *
 * <p>Имя {@code null} означает модуль по умолчанию: тот, под чьей ролью работает публикатор Outbox ({@code dgm.outbox.module}), а если он
 * не задан, первый из {@code dgm.db.modules}.
 */
public final class Modules {

    private final ModuleDatabases databases;
    private final Clock clock;
    private final String defaultModule;
    private final IdempotencyGuard.Settings idempotency;
    private final Map<String, OutboxWriter> outboxes = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyGuard> guards = new ConcurrentHashMap<>();

    public Modules(ModuleDatabases databases, Clock clock, String configuredDefault, IdempotencyGuard.Settings idempotency) {
        this.databases = databases;
        this.clock = clock;
        this.idempotency = idempotency;
        if (configuredDefault != null && !configuredDefault.isBlank()) {
            databases.jdbc(configuredDefault);
            this.defaultModule = configuredDefault;
        } else {
            List<String> names = databases.modules();
            if (names.isEmpty()) {
                throw new IllegalStateException("У сервиса нет ни одного модуля базы (dgm.db.modules)");
            }
            this.defaultModule = names.get(0);
        }
    }

    public String defaultModule() {
        return defaultModule;
    }

    public List<String> names() {
        return databases.modules();
    }

    public JdbcTemplate jdbc(String module) {
        return databases.jdbc(resolve(module));
    }

    /** Транзакция модуля: изменение данных и запись в Outbox идут в ней одной (ADR-005). */
    public TransactionOperations transactions(String module) {
        return databases.transactions(resolve(module));
    }

    /** Запись в Outbox через подключение модуля; вызывается внутри {@link #transactions(String)} того же модуля. */
    public OutboxWriter outbox(String module) {
        String name = resolve(module);
        return outboxes.computeIfAbsent(name, key -> new OutboxWriter(databases.jdbc(key), clock));
    }

    public ProcessedEvents processedEvents(String module) {
        return new JdbcProcessedEvents(jdbc(module));
    }

    /** Охранник идемпотентности модуля: ключи лежат в общей таблице базы сервиса, читаются и пишутся под ролью модуля. */
    public IdempotencyGuard idempotency(String module) {
        String name = resolve(module);
        return guards.computeIfAbsent(name, key -> new IdempotencyGuard(new JdbcIdempotencyStore(databases.jdbc(key)),
                databases.transactions(key), idempotency, clock));
    }

    private String resolve(String module) {
        return module == null || module.isBlank() ? defaultModule : module;
    }
}
