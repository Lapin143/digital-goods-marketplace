package dgm.platform.notification.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Лестница повторов письма: 1, 5, 25 секунд, затем недоставлено (ADR-011, шаг 8 Ф4). */
@Tag("TC-220")
class RetryPolicyTest {

    @Test
    void standardLadderIsOneFiveTwentyFiveSecondsAndThenGivesUp() {
        RetryPolicy policy = RetryPolicy.standard();

        assertEquals(4, policy.maxAttempts());
        assertEquals(Optional.of(Duration.ofSeconds(1)), policy.delayAfter(1));
        assertEquals(Optional.of(Duration.ofSeconds(5)), policy.delayAfter(2));
        assertEquals(Optional.of(Duration.ofSeconds(25)), policy.delayAfter(3));
        assertEquals(Optional.empty(), policy.delayAfter(4));
        assertEquals(Optional.empty(), policy.delayAfter(5));
    }

    @Test
    void policyWithoutDelaysMakesOneAttempt() {
        RetryPolicy policy = new RetryPolicy(List.of());

        assertEquals(1, policy.maxAttempts());
        assertEquals(Optional.empty(), policy.delayAfter(1));
    }

    @Test
    void invalidPoliciesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(null));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(List.of(Duration.ofSeconds(-1))));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(java.util.Collections.nCopies(10, Duration.ofSeconds(1))));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.standard().delayAfter(0));
    }
}
