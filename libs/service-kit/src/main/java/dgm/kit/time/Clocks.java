package dgm.kit.time;

import java.time.Clock;

/**
 * Единственное место, где берутся системные часы. Остальной код получает {@link Clock} извне, поэтому тесты подставляют
 * {@link ManualClock}. Прямые вызовы {@code Instant.now()}, {@code System.currentTimeMillis()} и подобные запрещены
 * правилом ArchUnit {@code ServiceArchRules.noDirectSystemClock} (conventions.md, раздел 5).
 */
public final class Clocks {

    private Clocks() {
    }

    /** Системные часы в UTC. */
    public static Clock system() {
        return Clock.systemUTC();
    }
}
