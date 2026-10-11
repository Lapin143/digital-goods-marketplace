package dgm.keycloak.smsotp.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimePadTest {

    @Test
    void shortWorkIsPaddedToMinimum() {
        long started = System.nanoTime();
        TimePad.padTo(started, Duration.ofMillis(150));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMillis >= 149, "прошло " + elapsedMillis + " мс");
        assertTrue(elapsedMillis < 1000, "прошло " + elapsedMillis + " мс");
    }

    @Test
    void longWorkIsNotDelayed() {
        long started = System.nanoTime() - Duration.ofMillis(500).toNanos();
        long before = System.nanoTime();
        TimePad.padTo(started, Duration.ofMillis(100));
        assertTrue((System.nanoTime() - before) / 1_000_000 < 50);
    }
}
