package dgm.platform.identity.otp.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** {@link ResponsePacer} по {@link Clock} сервиса: короткие паузы до границы, но не дольше разумного предела, если часы стоят. */
@Component
public final class ClockPacer implements ResponsePacer {

    private static final long STEP_MILLIS = 20;
    /** Предохранитель: часы, которые не идут (ошибка настройки), не должны подвесить запрос навсегда. */
    private static final long MAX_WAIT_MILLIS = 3_000;

    private final Clock clock;

    public ClockPacer(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void awaitUntil(Instant deadline) {
        long waited = 0;
        while (waited < MAX_WAIT_MILLIS) {
            Duration left = Duration.between(clock.instant(), deadline);
            if (left.isZero() || left.isNegative()) {
                return;
            }
            long pause = Math.min(STEP_MILLIS, Math.max(1, left.toMillis()));
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            waited += pause;
        }
    }
}
