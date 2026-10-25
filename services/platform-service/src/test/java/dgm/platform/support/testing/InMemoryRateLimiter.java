package dgm.platform.support.testing;

import dgm.platform.support.service.SupportRateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Скользящее окно в один час в памяти с теми же правилами, что у скрипта Redis: отказ ничего не записывает, окно освобождается по самой старой метке. */
public final class InMemoryRateLimiter implements SupportRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final Map<UUID, Deque<Instant>> marks = new HashMap<>();
    private int calls;

    @Override
    public Optional<Duration> admit(UUID buyerId, int limit, Instant now) {
        calls++;
        Deque<Instant> window = marks.computeIfAbsent(buyerId, id -> new ArrayDeque<>());
        while (!window.isEmpty() && !window.peekFirst().isAfter(now.minus(WINDOW))) {
            window.removeFirst();
        }
        if (window.size() >= limit) {
            return Optional.of(Duration.between(now, window.peekFirst().plus(WINDOW)));
        }
        window.addLast(now);
        return Optional.empty();
    }

    /** Сколько раз счётчик спрашивали: проверки до лимита его не тратят. */
    public int calls() {
        return calls;
    }
}
