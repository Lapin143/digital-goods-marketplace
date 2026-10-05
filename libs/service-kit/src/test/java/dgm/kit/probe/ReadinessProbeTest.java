package dgm.kit.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Проверка готовности для Docker: 200 только когда все проверки прошли, слушает только петлю. */
class ReadinessProbeTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

    private static HttpResponse<String> get(ReadinessProbe probe, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + probe.port() + path)).timeout(Duration.ofSeconds(5)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static ReadinessProbe started(BooleanSupplier... checks) throws IOException {
        ReadinessProbe probe = new ReadinessProbe(0, List.of(checks));
        probe.start();
        return probe;
    }

    @Test
    void readyWhenAllChecksPass() throws Exception {
        try (ReadinessProbe probe = started(() -> true, () -> true)) {
            HttpResponse<String> response = get(probe, "/ready");
            assertEquals(200, response.statusCode());
            assertEquals("ready", response.body());
        }
    }

    @Test
    void notReadyWhenAnyCheckFails() throws Exception {
        try (ReadinessProbe probe = started(() -> true, () -> false)) {
            HttpResponse<String> response = get(probe, "/ready");
            assertEquals(503, response.statusCode());
            assertEquals("not-ready", response.body());
        }
    }

    @Test
    void failingCheckCountsAsNotReady() throws Exception {
        try (ReadinessProbe probe = started(() -> {
            throw new IllegalStateException("база недоступна");
        })) {
            assertEquals(503, get(probe, "/ready").statusCode());
            assertFalse(probe.ready());
        }
    }

    @Test
    void readinessFollowsTheChecksOverTime() throws Exception {
        AtomicBoolean up = new AtomicBoolean(false);
        try (ReadinessProbe probe = started(up::get)) {
            assertEquals(503, get(probe, "/ready").statusCode());
            up.set(true);
            assertEquals(200, get(probe, "/ready").statusCode());
            up.set(false);
            assertEquals(503, get(probe, "/ready").statusCode());
        }
    }

    @Test
    void liveDoesNotDependOnChecks() throws Exception {
        try (ReadinessProbe probe = started(() -> false)) {
            HttpResponse<String> response = get(probe, "/live");
            assertEquals(200, response.statusCode());
            assertEquals("live", response.body());
        }
    }

    @Test
    void otherPathsAreNotFound() throws Exception {
        try (ReadinessProbe probe = started(() -> true)) {
            assertEquals(404, get(probe, "/actuator/env").statusCode());
            assertEquals(404, get(probe, "/").statusCode());
        }
    }

    @Test
    void probeWithoutChecksIsReady() throws Exception {
        try (ReadinessProbe probe = started()) {
            assertTrue(probe.ready());
            assertEquals(200, get(probe, "/ready").statusCode());
        }
    }

    @Test
    void closedProbeRefusesConnections() throws Exception {
        ReadinessProbe probe = started(() -> true);
        int port = probe.port();
        probe.close();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ready")).timeout(Duration.ofSeconds(2)).build();
        assertThrows(IOException.class, () -> CLIENT.send(request, HttpResponse.BodyHandlers.ofString()));
    }
}
