package dgm.platform.notification.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Повторы отправки: после первой неудачи 1 секунда, после второй 5, после третьей 25, после четвёртой уведомление считается
 * недоставленным (ADR-011, та же лестница, что у потребителя событий). Всего четыре попытки.
 */
public final class RetryPolicy {

    /** Лестница ADR-011. */
    public static final List<Duration> STANDARD_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(25));

    private final List<Duration> delays;

    public RetryPolicy(List<Duration> delays) {
        if (delays == null || delays.size() > 9) {
            throw new IllegalArgumentException("Повторов от нуля до девяти: столбец attempts ограничен десятью");
        }
        for (Duration delay : delays) {
            if (delay == null || delay.isNegative()) {
                throw new IllegalArgumentException("Пауза повтора не может быть отрицательной");
            }
        }
        this.delays = List.copyOf(delays);
    }

    public static RetryPolicy standard() {
        return new RetryPolicy(STANDARD_DELAYS);
    }

    /** Сколько попыток делается всего: первая и все повторы. */
    public int maxAttempts() {
        return delays.size() + 1;
    }

    /**
     * @param attemptsMade сколько попыток уже сделано, от 1
     * @return пауза до следующей попытки; пусто, если попытки кончились
     */
    public Optional<Duration> delayAfter(int attemptsMade) {
        if (attemptsMade < 1) {
            throw new IllegalArgumentException("Повтор назначается после первой попытки");
        }
        return attemptsMade <= delays.size() ? Optional.of(delays.get(attemptsMade - 1)) : Optional.empty();
    }
}
