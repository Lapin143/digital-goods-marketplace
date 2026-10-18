package dgm.kit.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Расписание повторов: разбор строки, число попыток, границы разброса ±20 %, расчёт наибольшего срока. */
class RetryPolicyTest {

    @Test
    void scheduleFromAdr011IsParsedWithAllUnits() {
        RetryPolicy policy = RetryPolicy.parse("10s,30s,2m,5m,10m", 0.2, new Random(1));
        assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5),
                Duration.ofMinutes(10)), policy.basePauses());
        assertEquals(5, policy.maxRetries());
        assertEquals(6, policy.maxAttempts());
        assertEquals(List.of(Duration.ofMillis(300), Duration.ofMillis(700), Duration.ofHours(1)),
                RetryPolicy.parse(" 300ms , 700ms,1h", 0, new Random(1)).basePauses());
    }

    @Test
    void emptyScheduleMeansSingleAttempt() {
        assertEquals(1, RetryPolicy.parse("", 0.2, new Random(1)).maxAttempts());
        assertEquals(1, RetryPolicy.parse(null, 0.2, new Random(1)).maxAttempts());
        assertEquals(1, RetryPolicy.none().maxAttempts());
        assertEquals(0, RetryPolicy.none().maxRetries());
        assertEquals(Duration.ZERO, RetryPolicy.none().totalMaxPause());
    }

    @Test
    void pauseStaysWithinTwentyPercentAndActuallyVaries() {
        RetryPolicy policy = RetryPolicy.parse("10s,30s", 0.2, new Random(42));
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < 2000; i++) {
            long millis = policy.pause(0).toMillis();
            assertTrue(millis >= 8_000 && millis <= 12_000, "пауза вне ±20 %: " + millis);
            min = Math.min(min, millis);
            max = Math.max(max, millis);
        }
        assertTrue(min < 8_300, "нижняя граница разброса не достигается: " + min);
        assertTrue(max > 11_700, "верхняя граница разброса не достигается: " + max);
        long second = policy.pause(1).toMillis();
        assertTrue(second >= 24_000 && second <= 36_000, "вторая пауза вне ±20 %: " + second);
    }

    @Test
    void zeroJitterGivesExactPauses() {
        RetryPolicy policy = RetryPolicy.parse("1s,3s", 0, new Random(7));
        assertEquals(Duration.ofSeconds(1), policy.pause(0));
        assertEquals(Duration.ofSeconds(3), policy.pause(1));
    }

    @Test
    void seededRandomMakesPausesRepeatable() {
        RetryPolicy a = RetryPolicy.parse("10s,30s", 0.2, new Random(5));
        RetryPolicy b = RetryPolicy.parse("10s,30s", 0.2, new Random(5));
        assertEquals(a.pause(0), b.pause(0));
        assertEquals(a.pause(1), b.pause(1));
    }

    @Test
    void maxPauseAndTotalIncludeJitter() {
        RetryPolicy policy = RetryPolicy.parse("10s,30s", 0.2, new Random(1));
        assertEquals(Duration.ofSeconds(12), policy.maxPause(0));
        assertEquals(Duration.ofSeconds(36), policy.maxPause(1));
        assertEquals(Duration.ofSeconds(48), policy.totalMaxPause());
    }

    @Test
    void wrongInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.parse("10", 0.2, new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.parse("10s,,30s", 0.2, new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.parse("-5s", 0.2, new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.parse("0s", 0.2, new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.parse("10s", 1.0, new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.parse("10s", -0.1, new Random(1)));
    }

    @Test
    void pauseBeyondScheduleIsAnError() {
        RetryPolicy policy = RetryPolicy.parse("1s", 0, new Random(1));
        assertThrows(IndexOutOfBoundsException.class, () -> policy.pause(1));
    }
}
