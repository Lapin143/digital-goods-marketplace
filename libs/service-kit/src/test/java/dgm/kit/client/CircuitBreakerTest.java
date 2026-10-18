package dgm.kit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.client.CircuitBreaker.State;
import dgm.kit.time.ManualClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Предохранитель ADR-015: окно из 10 вызовов, порог 50 %, пауза 30 с, одна пробная попытка в полуоткрытом состоянии. */
class CircuitBreakerTest {

    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-18T10:00:00Z"));

    private CircuitBreaker breaker() {
        return new CircuitBreaker(10, 0.5, Duration.ofSeconds(30), clock);
    }

    private static void call(CircuitBreaker b, boolean failure) {
        assertTrue(b.tryAcquire());
        if (failure) {
            b.recordFailure();
        } else {
            b.recordSuccess();
        }
    }

    @Test
    void closedUntilWindowIsFullEvenIfAllCallsFail() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 9; i++) {
            call(b, true);
        }
        assertEquals(State.CLOSED, b.state());
        call(b, true);
        assertEquals(State.OPEN, b.state());
    }

    @Test
    void opensExactlyAtThreshold() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 5; i++) {
            call(b, false);
        }
        for (int i = 0; i < 4; i++) {
            call(b, true);
        }
        assertEquals(State.CLOSED, b.state());
        call(b, true);
        assertEquals(State.OPEN, b.state());
    }

    @Test
    void staysClosedBelowThreshold() {
        CircuitBreaker b = breaker();
        // 40 % неудач в любом окне из 10 вызовов: период «три успеха, две неудачи»
        for (int round = 0; round < 12; round++) {
            call(b, false);
            call(b, false);
            call(b, false);
            call(b, true);
            call(b, true);
            assertEquals(State.CLOSED, b.state());
        }
    }

    @Test
    void oldFailuresLeaveTheWindow() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 4; i++) {
            call(b, true);
        }
        for (int i = 0; i < 6; i++) {
            call(b, false);
        }
        // Следующие 10 успехов вытесняют все прежние результаты
        for (int i = 0; i < 10; i++) {
            call(b, false);
        }
        for (int i = 0; i < 4; i++) {
            call(b, true);
        }
        assertEquals(State.CLOSED, b.state());
    }

    @Test
    void openRejectsCallsUntilPauseHasPassed() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 10; i++) {
            call(b, true);
        }
        assertFalse(b.tryAcquire());
        clock.advance(Duration.ofSeconds(29));
        assertFalse(b.tryAcquire());
        assertEquals(State.OPEN, b.state());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(State.HALF_OPEN, b.state());
        assertTrue(b.tryAcquire());
    }

    @Test
    void halfOpenAllowsOnlyOneProbe() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 10; i++) {
            call(b, true);
        }
        clock.advance(Duration.ofSeconds(30));
        assertTrue(b.tryAcquire());
        assertFalse(b.tryAcquire());
        assertFalse(b.tryAcquire());
    }

    @Test
    void successfulProbeClosesAndClearsHistory() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 10; i++) {
            call(b, true);
        }
        clock.advance(Duration.ofSeconds(30));
        call(b, false);
        assertEquals(State.CLOSED, b.state());
        // История очищена: девять неудач подряд снова не открывают предохранитель
        for (int i = 0; i < 9; i++) {
            call(b, true);
        }
        assertEquals(State.CLOSED, b.state());
    }

    @Test
    void failedProbeReopensForAFullPause() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 10; i++) {
            call(b, true);
        }
        clock.advance(Duration.ofSeconds(30));
        call(b, true);
        assertEquals(State.OPEN, b.state());
        clock.advance(Duration.ofSeconds(29));
        assertFalse(b.tryAcquire());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(b.tryAcquire());
    }

    @Test
    void abandonedProbeFreesTheSlot() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 10; i++) {
            call(b, true);
        }
        clock.advance(Duration.ofSeconds(30));
        assertTrue(b.tryAcquire());
        b.abandon();
        assertEquals(State.HALF_OPEN, b.state());
        assertTrue(b.tryAcquire());
    }

    @Test
    void lateResultOfCallStartedBeforeOpeningIsIgnored() {
        CircuitBreaker b = breaker();
        assertTrue(b.tryAcquire());
        for (int i = 0; i < 10; i++) {
            call(b, true);
        }
        assertEquals(State.OPEN, b.state());
        b.recordSuccess();
        assertEquals(State.OPEN, b.state());
    }

    @Test
    void wrongParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(0, 0.5, Duration.ofSeconds(1), clock));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(10, 0, Duration.ofSeconds(1), clock));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(10, 1.5, Duration.ofSeconds(1), clock));
    }
}
