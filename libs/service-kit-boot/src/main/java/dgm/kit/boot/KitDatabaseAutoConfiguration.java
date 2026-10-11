package dgm.kit.boot;

import dgm.kit.events.JdbcProcessedEvents;
import dgm.kit.idempotency.IdempotencyGuard;
import dgm.kit.idempotency.JdbcIdempotencyStore;
import dgm.kit.outbox.OutboxCleaner;
import dgm.kit.tls.TlsMaterial;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * База сервиса: миграции при старте, пулы по модулям, реестр модулей и ежедневная очистка служебных таблиц. Включается, когда задано
 * {@code dgm.db.database}.
 */
@AutoConfiguration(after = KitCoreAutoConfiguration.class)
@ConditionalOnProperty(prefix = "dgm.db", name = "database")
public class KitDatabaseAutoConfiguration {

    @Bean(destroyMethod = "close")
    ModuleDatabases moduleDatabases(KitProperties properties, SecretsDirectory secrets, TlsMaterial tls) {
        return ModuleDatabases.open(properties.db(), secrets, tls, properties.service());
    }

    /** Транзакция, запись в Outbox, учёт событий и охранник идемпотентности по модулям; сервисы берут их отсюда. */
    @Bean
    Modules modules(KitProperties properties, ModuleDatabases databases, Clock clock) {
        KitProperties.Idempotency idempotency = properties.idempotency();
        IdempotencyGuard.Settings settings = new IdempotencyGuard.Settings(idempotency.waitForOther(), IdempotencyGuard.Settings.defaults().pollInterval(),
                idempotency.abandonedAfter());
        return new Modules(databases, clock, properties.outbox().module(), settings);
    }

    /**
     * Ежедневная очистка в базе сервиса: отправленные строки Outbox старше 3 суток, обработанные события старше 14 суток и ключи
     * идемпотентности старше 24 часов (ADR-005, ADR-006). Таблицы общие для модулей, поэтому очистка идёт под ролью модуля по умолчанию.
     */
    @Bean(destroyMethod = "close")
    KitMaintenance kitMaintenance(Modules modules) {
        OutboxCleaner outbox = new OutboxCleaner(modules.jdbc(null));
        JdbcProcessedEvents processed = new JdbcProcessedEvents(modules.jdbc(null));
        JdbcIdempotencyStore keys = new JdbcIdempotencyStore(modules.jdbc(null));
        return KitMaintenance.start(Duration.ofMinutes(1), Duration.ofDays(1), outbox::clean, processed::clean,
                () -> keys.purge(IdempotencyGuard.RETENTION.toSeconds()));
    }
}
