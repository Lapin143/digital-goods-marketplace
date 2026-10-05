package dgm.kit.boot;

import dgm.kit.probe.ReadinessProbe;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.annotation.Bean;

/** Проверка готовности на 127.0.0.1: приложение приняло трафик и все пулы базы отвечают. */
@AutoConfiguration(after = KitDatabaseAutoConfiguration.class)
@ConditionalOnProperty(prefix = "dgm.probe", name = "enabled", matchIfMissing = true)
public class KitProbeAutoConfiguration {

    @Bean(initMethod = "start", destroyMethod = "close")
    ReadinessProbe readinessProbe(KitProperties properties, ApplicationAvailability availability, ObjectProvider<ModuleDatabases> databases)
            throws IOException {
        List<BooleanSupplier> checks = new ArrayList<>();
        checks.add(() -> availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC);
        ModuleDatabases db = databases.getIfAvailable();
        if (db != null) {
            checks.add(db::healthy);
        }
        return new ReadinessProbe(properties.probe().port(), checks);
    }
}
