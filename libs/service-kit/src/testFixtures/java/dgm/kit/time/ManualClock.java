package dgm.kit.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Часы для тестов: время стоит на месте, пока его не передвинут. Потокобезопасны. */
public final class ManualClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    public ManualClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private ManualClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public synchronized void advance(Duration by) {
        now = now.plus(by);
    }

    public synchronized void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new ManualClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
