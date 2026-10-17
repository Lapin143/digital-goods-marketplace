package dgm.kit.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Помощник гонок: участники действительно работают одновременно, ошибка участника и зависание не теряются. */
class ConcurrentTest {

    @Test
    void allParticipantsAreInsideAtTheSameTime() {
        // Барьер на восемь сторон проходит, только если все восемь потоков живы одновременно
        CyclicBarrier barrier = new CyclicBarrier(8);
        List<Integer> results = Concurrent.race(8, index -> {
            barrier.await(20, TimeUnit.SECONDS);
            return index;
        });
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), results);
    }

    @Test
    void theWorkStartsOnlyAfterEveryParticipantIsReady() {
        AtomicInteger started = new AtomicInteger();
        List<Integer> seen = Concurrent.race(6, index -> {
            started.incrementAndGet();
            Thread.sleep(300);
            return started.get();
        });
        // Все шестеро успели начать, пока первый спал: защёлка открыта для всех сразу
        assertTrue(seen.stream().allMatch(count -> count == 6), seen.toString());
    }

    @Test
    void aParticipantsExceptionFailsTheRaceAndKeepsItsCause() {
        Concurrent.Failure failure = assertThrows(Concurrent.Failure.class, () -> Concurrent.race(3, index -> {
            if (index == 1) {
                throw new IllegalStateException("сбой участника");
            }
            return index;
        }));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("сбой участника"));
    }

    @Test
    void aParticipantThatNeverFinishesIsReportedByTheTimeout() {
        Concurrent.Failure failure = assertThrows(Concurrent.Failure.class, () -> Concurrent.race(2, Duration.ofMillis(300), index -> {
            Thread.sleep(30_000);
            return index;
        }));
        assertTrue(failure.getMessage().contains("не закончили"), failure.getMessage());
    }

    @Test
    void aRaceNeedsAtLeastTwoParticipants() {
        assertThrows(IllegalArgumentException.class, () -> Concurrent.race(1, index -> index));
    }
}
