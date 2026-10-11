package dgm.platform.identity.otp.testing;

import dgm.kit.problem.ProblemException;
import dgm.platform.identity.otp.service.OtpStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Хранилище кодов в памяти с теми же правилами, что у скриптов Lua ({@code otp-window.lua}, {@code otp-redeem.lua}): время берётся из
 * параметров вызова и из часов теста, поэтому срок кода, окна и замки проверяются без ожидания. Все операции неделимы (synchronized),
 * как скрипты в Redis. Правила скриптов проверяются отдельно на настоящем Redis ({@code RedisOtpStoreIT}).
 */
public final class InMemoryOtpStore implements OtpStore {

    private record Entry(Code code, Instant retainUntil) {
    }

    private final Clock clock;
    private final Map<String, Entry> codes = new HashMap<>();
    private final Map<String, List<Long>> sliding = new HashMap<>();
    /** Счётчики с фиксированным окном: {значение, момент окончания окна, мс}. */
    private final Map<String, long[]> counters = new HashMap<>();
    private final Map<String, long[]> bursts = new HashMap<>();
    private final Map<String, Long> locks = new HashMap<>();
    private final Map<String, Instant> marks = new HashMap<>();
    private boolean down;

    public InMemoryOtpStore(Clock clock) {
        this.clock = clock;
    }

    /** Redis недоступен: любая операция отвечает 503, как клиент каркаса. */
    public synchronized void down(boolean value) {
        this.down = value;
    }

    /** Записи кодов для проверок: ключ и запись (хеш, попытки). */
    public synchronized Map<String, Code> codes() {
        Map<String, Code> copy = new HashMap<>();
        codes.forEach((key, entry) -> copy.put(key, entry.code()));
        return copy;
    }

    public synchronized Optional<Code> code(String keyPart) {
        return codes.entrySet().stream().filter(e -> e.getKey().contains(keyPart)).map(e -> e.getValue().code()).findFirst();
    }

    public synchronized boolean hasLock() {
        return !locks.isEmpty();
    }

    /** Все ключи, которые хранилище держит сейчас: проверка, что в ключах нет открытых номеров и адресов. */
    public synchronized java.util.Set<String> keys() {
        java.util.Set<String> keys = new TreeSet<>(codes.keySet());
        keys.addAll(sliding.keySet());
        keys.addAll(counters.keySet());
        keys.addAll(bursts.keySet());
        keys.addAll(locks.keySet());
        return keys;
    }

    private void ensureUp() {
        if (down) {
            throw ProblemException.dependencyUnavailable("Redis недоступен.", 5);
        }
    }

    @Override
    public synchronized Admission admit(List<Window> windows, Instant now) {
        ensureUp();
        long nowMs = now.toEpochMilli();
        int n = windows.size();
        long[] used = new long[n];
        Long[] oldest = new Long[n];
        long denied = 0;
        for (int i = 0; i < n; i++) {
            Window w = windows.get(i);
            long span = w.span().toMillis();
            long count;
            long free;
            if (!w.counter()) {
                List<Long> stamps = sliding.computeIfAbsent(w.key(), k -> new ArrayList<>());
                stamps.removeIf(t -> t <= nowMs - span);
                count = stamps.size();
                if (count > 0) {
                    oldest[i] = Collections.min(stamps);
                }
                free = (oldest[i] != null ? oldest[i] : nowMs) + span - nowMs;
            } else {
                long[] c = counters.get(w.key());
                if (c != null && c[1] <= nowMs) {
                    counters.remove(w.key());
                    c = null;
                }
                count = c == null ? 0 : c[0];
                free = c == null ? span : c[1] - nowMs;
            }
            used[i] = count;
            if (count >= w.limit()) {
                denied = Math.max(denied, Math.max(free, 1));
            }
        }
        if (denied > 0) {
            return Admission.denied(Duration.ofMillis(denied));
        }
        Long remaining = null;
        long resend = 0;
        for (int i = 0; i < n; i++) {
            Window w = windows.get(i);
            long span = w.span().toMillis();
            if (!w.counter()) {
                sliding.get(w.key()).add(nowMs);
                long left = w.limit() - used[i] - 1;
                remaining = remaining == null ? left : Math.min(remaining, left);
                if (left == 0) {
                    resend = Math.max(resend, (oldest[i] != null ? oldest[i] : nowMs) + span - nowMs);
                }
            } else {
                long[] c = counters.computeIfAbsent(w.key(), k -> new long[] {0, nowMs + span});
                c[0]++;
            }
        }
        int left = remaining == null ? 0 : remaining.intValue();
        return Admission.allowed(left, Duration.ofMillis(left > 0 ? 0 : resend));
    }

    @Override
    public synchronized void put(String key, Code code, Duration retention) {
        ensureUp();
        codes.put(key, new Entry(code, clock.instant().plus(retention)));
    }

    @Override
    public synchronized void remove(String key) {
        ensureUp();
        codes.remove(key);
    }

    @Override
    public synchronized Redemption redeem(Redeem request, Instant now) {
        ensureUp();
        long nowMs = now.toEpochMilli();
        Optional<Tracking> tracking = request.tracking();
        if (tracking.isPresent()) {
            Long until = locks.get(tracking.get().lockKey());
            if (until != null && nowMs < until) {
                return new Redemption(Result.LOCKED, "", "", 0, Duration.ofMillis(Math.max(1, until - nowMs)));
            }
            locks.remove(tracking.get().lockKey());
        }
        Entry entry = codes.get(request.codeKey());
        // Redis считает ключ истёкшим, когда время строго больше момента окончания
        if (entry != null && clock.instant().isAfter(entry.retainUntil())) {
            codes.remove(request.codeKey());
            entry = null;
        }
        if (entry == null) {
            return Redemption.of(Result.ABSENT);
        }
        Code code = entry.code();
        if (!request.confirmationId().isEmpty() && !code.confirmationId().equals(request.confirmationId())) {
            return Redemption.of(Result.OTHER);
        }
        if (nowMs > code.expiresAt().toEpochMilli()) {
            return Redemption.of(Result.EXPIRED);
        }
        if (code.hash().equals(request.hash()) && !code.userId().isEmpty()) {
            codes.remove(request.codeKey());
            tracking.ifPresent(t -> bursts.remove(t.burnKey()));
            return new Redemption(Result.VALID, code.userId(), code.phone(), 0, Duration.ZERO);
        }
        int left = code.attempts() - 1;
        if (left <= 0) {
            codes.remove(request.codeKey());
            if (tracking.isPresent()) {
                Tracking t = tracking.get();
                long[] burst = bursts.get(t.burnKey());
                if (burst != null && burst[1] <= nowMs) {
                    bursts.remove(t.burnKey());
                    burst = null;
                }
                if (burst == null) {
                    burst = new long[] {0, nowMs + t.burnWindow().toMillis()};
                    bursts.put(t.burnKey(), burst);
                }
                burst[0]++;
                if (burst[0] >= t.burnLimit()) {
                    locks.put(t.lockKey(), nowMs + t.lockFor().toMillis());
                    bursts.remove(t.burnKey());
                    return new Redemption(Result.EXHAUSTED, "", "", 0, t.lockFor());
                }
            }
            return new Redemption(Result.EXHAUSTED, "", "", 0, Duration.ZERO);
        }
        codes.put(request.codeKey(), new Entry(new Code(code.hash(), code.expiresAt(), left, code.userId(), code.confirmationId(), code.phone()),
                entry.retainUntil()));
        return new Redemption(Result.INVALID, "", "", left, Duration.ZERO);
    }

    @Override
    public synchronized boolean markOnce(String key, Duration ttl) {
        ensureUp();
        Instant until = marks.get(key);
        if (until != null && !clock.instant().isAfter(until)) {
            return false;
        }
        marks.put(key, clock.instant().plus(ttl));
        return true;
    }

    @Override
    public synchronized Optional<Duration> lockedFor(String lockKey, Instant now) {
        ensureUp();
        Long until = locks.get(lockKey);
        return until != null && until > now.toEpochMilli() ? Optional.of(Duration.ofMillis(until - now.toEpochMilli())) : Optional.empty();
    }
}
