package dgm.kit.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

class KitDefaultsListenerTest {

    /** Среда только с указанными значениями: переменные окружения и свойства JVM машины в проверку не попадают. */
    private static MockEnvironment environment(Map<String, String> own) {
        MockEnvironment env = new MockEnvironment();
        own.forEach(env::setProperty);
        return env;
    }

    @Test
    void defaultsAreResolvedAgainstServiceName() {
        MockEnvironment env = environment(Map.of("spring.application.name", "demo-service"));
        KitDefaultsListener.apply(env);
        assertEquals("demo-service", env.getProperty("dgm.service"));
        assertEquals("8443", env.getProperty("server.port"));
        assertEquals("8444", env.getProperty("management.server.port"));
        assertEquals("need", env.getProperty("server.ssl.client-auth"));
        assertEquals("file:/run/secrets/tls_demo-service.crt", env.getProperty("spring.ssl.bundle.pem.dgm.keystore.certificate"));
        assertEquals("demo-service", env.getProperty("management.metrics.tags.application"));
        assertEquals("logstash", env.getProperty("logging.structured.format.console"));
    }

    @Test
    void secretsDirectoryFromServiceIsUsedInPaths() {
        MockEnvironment env = environment(Map.of("spring.application.name", "demo-service", "dgm.secrets.dir", "/tmp/s"));
        KitDefaultsListener.apply(env);
        assertEquals("file:/tmp/s/tls_ca.crt", env.getProperty("spring.ssl.bundle.pem.dgm.truststore.certificate"));
    }

    @Test
    void serviceValuesOverrideDefaults() {
        MockEnvironment env = environment(Map.of("spring.application.name", "demo-service", "server.port", "9000"));
        KitDefaultsListener.apply(env);
        assertEquals("9000", env.getProperty("server.port"));
        assertEquals("8444", env.getProperty("management.server.port"));
    }

    @Test
    void applyingTwiceKeepsOneSource() {
        MockEnvironment env = environment(Map.of("spring.application.name", "demo-service"));
        KitDefaultsListener.apply(env);
        KitDefaultsListener.apply(env);
        long count = env.getPropertySources().stream().filter(s -> s.getName().equals(KitDefaultsListener.SOURCE_NAME)).count();
        assertEquals(1, count);
    }

    @Test
    void defaultsAreTheLowestPriority() {
        MockEnvironment env = environment(Map.of());
        KitDefaultsListener.apply(env);
        var names = env.getPropertySources().stream().map(s -> s.getName()).toList();
        assertEquals(KitDefaultsListener.SOURCE_NAME, names.get(names.size() - 1));
        assertNull(env.getProperty("dgm.secrets.dir"));
    }

    @Test
    void runsBetweenConfigLoadingAndLoggingSetup() {
        int order = new KitDefaultsListener().getOrder();
        assertTrue(order > Ordered.HIGHEST_PRECEDENCE + 10 && order < Ordered.HIGHEST_PRECEDENCE + 20, "порядок " + order);
    }
}
