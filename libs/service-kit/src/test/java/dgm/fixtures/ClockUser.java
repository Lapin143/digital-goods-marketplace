package dgm.fixtures;

import java.time.Instant;
import java.time.LocalDate;

/** Нарушение: системные часы напрямую. */
public final class ClockUser {

    public Instant instant() {
        return Instant.now();
    }

    public LocalDate date() {
        return LocalDate.now();
    }

    public long millis() {
        return System.currentTimeMillis();
    }
}
