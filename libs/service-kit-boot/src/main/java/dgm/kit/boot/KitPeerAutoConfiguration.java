package dgm.kit.boot;

import dgm.kit.retry.Sleeper;
import dgm.kit.tls.TlsMaterial;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Клиенты соседних сервисов и внешних систем (Р-140): реестр {@link PeerClients} по настройкам {@code dgm.peers.*}. Клиент создаётся
 * при первом обращении, поэтому сервис без соседей ничего не платит, а сервис с соседями стартует и при их недоступности.
 */
@AutoConfiguration(after = KitCoreAutoConfiguration.class)
public class KitPeerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Sleeper peerSleeper() {
        return Sleeper.SYSTEM;
    }

    @Bean
    @ConditionalOnMissingBean
    PeerClients peerClients(KitProperties properties, TlsMaterial tls, Clock clock, Sleeper sleeper, ObjectProvider<MeterRegistry> metrics) {
        return new PeerClients(properties, tls, clock, sleeper, metrics.getIfAvailable());
    }
}
