package dgm.kit.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TimestampsTest {

    @Test
    void formatsUtcWithMillisecondsAndZ() {
        assertEquals("2026-10-03T12:00:00.123Z", Timestamps.format(Instant.parse("2026-10-03T12:00:00.123Z")));
    }

    @Test
    void padsMillisecondsAndDropsMicroseconds() {
        assertEquals("2026-10-03T12:00:00.000Z", Timestamps.format(Instant.parse("2026-10-03T12:00:00Z")));
        assertEquals("2026-10-03T12:00:00.123Z", Timestamps.format(Instant.parse("2026-10-03T12:00:00.123987Z")));
    }

    @Test
    void parsesAnyOffsetToInstant() {
        assertEquals(Optional.of(Instant.parse("2026-10-03T12:00:00Z")), Timestamps.parse("2026-10-03T15:00:00+03:00"));
        assertEquals(Optional.of(Instant.parse("2026-10-03T12:00:00.500Z")), Timestamps.parse("2026-10-03T12:00:00.5Z"));
    }

    @Test
    void parseOfWrongTextIsEmpty() {
        assertTrue(Timestamps.parse("2026-10-03").isEmpty());
        assertTrue(Timestamps.parse("2026-10-03T12:00:00").isEmpty());
        assertTrue(Timestamps.parse("").isEmpty());
    }

    @Test
    void formatThenParseIsStable() {
        Instant t = Instant.parse("2026-01-31T23:59:59.999Z");
        assertEquals(Optional.of(t), Timestamps.parse(Timestamps.format(t)));
    }
}
