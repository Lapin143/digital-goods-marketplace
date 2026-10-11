package dgm.platform.identity.otp.testing;

import dgm.kit.time.ManualClock;
import dgm.platform.identity.otp.service.ResponsePacer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Выравнивание ответа на часах теста: «ожидание» передвигает часы до границы и запоминает её. */
public final class ClockedPacer implements ResponsePacer {

    private final ManualClock clock;
    private final List<Instant> deadlines = new ArrayList<>();

    public ClockedPacer(ManualClock clock) {
        this.clock = clock;
    }

    @Override
    public void awaitUntil(Instant deadline) {
        deadlines.add(deadline);
        if (clock.instant().isBefore(deadline)) {
            clock.set(deadline);
        }
    }

    public List<Instant> deadlines() {
        return List.copyOf(deadlines);
    }
}
