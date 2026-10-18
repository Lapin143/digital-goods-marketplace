package dgm.kit.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Предохранитель вызовов соседа или внешней системы (ADR-015: 50 % ошибок на последних 10 вызовах, пауза 30 секунд).
 *
 * <p>Состояния: {@code CLOSED} (вызовы идут, результаты последних {@code window} вызовов копятся в кольце), {@code OPEN} (вызовы
 * отклоняются сразу, без обращения к соседу, пока не прошла пауза) и {@code HALF_OPEN} (после паузы пропускается один пробный вызов:
 * успех закрывает предохранитель и очищает кольцо, неудача открывает его снова на новую паузу). Предохранитель открывается, когда
 * в кольце накопилось {@code window} результатов и доля ошибок не меньше порога. Неудачей считается недоступность соседа (сеть,
 * тайм-аут, ответ 5xx), а не отказ по смыслу (4xx). Время берётся из внедряемых часов. Потокобезопасен.
 */
public final class CircuitBreaker {

    /** Состояние предохранителя; порядковый номер идёт в метрику. */
    public enum State { CLOSED, HALF_OPEN, OPEN }

    private final int window;
    private final double failureRatio;
    private final Duration openFor;
    private final Clock clock;
    private final boolean[] failures;

    private State state = State.CLOSED;
    private int recorded;
    private int next;
    private int failureCount;
    private Instant openedAt = Instant.MIN;
    private boolean probeInFlight;

    public CircuitBreaker(int window, double failureRatio, Duration openFor, Clock clock) {
        if (window < 1) {
            throw new IllegalArgumentException("Окно предохранителя должно быть не меньше 1");
        }
        if (failureRatio <= 0 || failureRatio > 1) {
            throw new IllegalArgumentException("Порог ошибок должен быть больше 0 и не больше 1");
        }
        this.window = window;
        this.failureRatio = failureRatio;
        this.openFor = Objects.requireNonNull(openFor, "openFor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.failures = new boolean[window];
    }

    /**
     * Можно ли делать вызов сейчас. После {@code true} вызывающий обязан сообщить итог через {@link #recordSuccess()} или
     * {@link #recordFailure()}: в состоянии {@code HALF_OPEN} пробный вызов один, и до его итога остальные отклоняются.
     */
    public synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.instant().isBefore(openedAt.plus(openFor))) {
                    return false;
                }
                state = State.HALF_OPEN;
                probeInFlight = true;
                return true;
            default:
                if (probeInFlight) {
                    return false;
                }
                probeInFlight = true;
                return true;
        }
    }

    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            reset();
            return;
        }
        if (state == State.CLOSED) {
            record(false);
        }
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        if (state == State.CLOSED) {
            record(true);
            if (recorded >= window && (double) failureCount / window >= failureRatio) {
                open();
            }
        }
    }

    /**
     * Вызов прерван, итога нет (поток прерван при ожидании): в полуоткрытом состоянии освобождает место пробного вызова, остальные
     * состояния не меняет.
     */
    public synchronized void abandon() {
        if (state == State.HALF_OPEN) {
            probeInFlight = false;
        }
    }

    public synchronized State state() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openFor))) {
            return State.HALF_OPEN;
        }
        return state;
    }

    private void record(boolean failure) {
        if (recorded == window && failures[next]) {
            failureCount--;
        }
        failures[next] = failure;
        if (failure) {
            failureCount++;
        }
        next = (next + 1) % window;
        if (recorded < window) {
            recorded++;
        }
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.instant();
        probeInFlight = false;
    }

    private void reset() {
        state = State.CLOSED;
        probeInFlight = false;
        recorded = 0;
        next = 0;
        failureCount = 0;
        java.util.Arrays.fill(failures, false);
    }
}
