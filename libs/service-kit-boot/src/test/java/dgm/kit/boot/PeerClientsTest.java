package dgm.kit.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dgm.kit.client.PeerClient;
import dgm.kit.client.PeerRequest;
import dgm.kit.retry.Sleeper;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Реестр клиентов соседей: создание по настройкам, переиспользование, срок вызова по умолчанию, отказ для неописанного соседа. */
class PeerClientsTest {

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"pong\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private PeerClients clients(Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(extra);
        values.put("dgm.service", "order-service");
        KitProperties properties = new Binder(new MapConfigurationPropertySource(values)).bind("dgm", KitProperties.class).get();
        return new PeerClients(properties, null, new ManualClock(Instant.parse("2026-10-18T10:00:00Z")), Sleeper.SYSTEM, new SimpleMeterRegistry());
    }

    private String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void clientIsCreatedFromSettingsOnceAndReused() {
        PeerClients clients = clients(Map.of("dgm.peers.inventory-service.base-url", url(), "dgm.peers.inventory-service.attempt-timeout", "1s"));
        PeerClient client = clients.get("inventory-service");
        assertSame(client, clients.get("inventory-service"));
        assertEquals("inventory-service", client.peer());
        assertEquals(Duration.ofSeconds(1), client.settings().attemptTimeout());
        assertEquals(Boolean.TRUE, client.call(PeerRequest.get("/ping")).json().get("pong"));
    }

    @Test
    void totalDeadlineDefaultsToAttemptsPlusLongestPauses() {
        // 3 попытки по 2 с и паузы 300 мс и 700 мс с разбросом 20 %: 6 с + 1,2 с
        PeerClient client = clients(Map.of("dgm.peers.a.base-url", url())).get("a");
        assertEquals(3, client.settings().retry().maxAttempts());
        assertEquals(Duration.ofMillis(7200), client.settings().totalDeadline());
    }

    @Test
    void explicitTotalDeadlineAndEmptyRetryAreHonoured() {
        PeerClient client = clients(Map.of("dgm.peers.a.base-url", url(), "dgm.peers.a.retry", "", "dgm.peers.a.total-deadline", "5s")).get("a");
        assertEquals(1, client.settings().retry().maxAttempts());
        assertEquals(Duration.ofSeconds(5), client.settings().totalDeadline());
    }

    @Test
    void peerWithoutSettingsOrAddressIsRefused() {
        PeerClients clients = clients(Map.of("dgm.peers.noaddr.attempt-timeout", "1s"));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> clients.get("payment-service"));
        assertTrue(unknown.getMessage().contains("dgm.peers.payment-service.base-url"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> clients.get("noaddr"));
    }
}
