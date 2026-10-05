package dgm.kit.boot;

import dgm.kit.tls.TlsMaterial;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** База сервиса: миграции при старте и пулы по модулям. Включается, когда задано {@code dgm.db.database}. */
@AutoConfiguration(after = KitCoreAutoConfiguration.class)
@ConditionalOnProperty(prefix = "dgm.db", name = "database")
public class KitDatabaseAutoConfiguration {

    @Bean(destroyMethod = "close")
    ModuleDatabases moduleDatabases(KitProperties properties, SecretsDirectory secrets, TlsMaterial tls) {
        return ModuleDatabases.open(properties.db(), secrets, tls, properties.service());
    }
}
