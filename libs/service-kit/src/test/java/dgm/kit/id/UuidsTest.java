package dgm.kit.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidsTest {

    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-04T10:00:00Z"));

    @Test
    void hasVersion7AndRfcVariant() {
        UUID id = Uuids.v7(clock);
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void carriesTheClockTime() {
        UUID id = Uuids.v7(clock);
        assertEquals(clock.millis(), Uuids.timestampMillis(id));
    }

    @Test
    void laterTimeGivesLargerPrefix() {
        UUID first = Uuids.v7(clock);
        clock.advance(Duration.ofMillis(5));
        UUID second = Uuids.v7(clock);
        assertTrue(Uuids.timestampMillis(second) > Uuids.timestampMillis(first));
        assertTrue(first.toString().substring(0, 13).compareTo(second.toString().substring(0, 13)) < 0);
    }

    @Test
    void sameMillisecondStillGivesDifferentIds() {
        assertNotEquals(Uuids.v7(clock), Uuids.v7(clock));
    }

    @Test
    void timestampOfOtherVersionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Uuids.timestampMillis(UUID.randomUUID()));
    }
}
