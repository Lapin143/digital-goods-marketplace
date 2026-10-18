package dgm.payment.payments.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Расписание автовозврата (ADR-015): паузы, разброс ±20 %, горизонт 17,5 минуты, разбор настройки и границы попыток. */
@Tag("TC-520")
class RefundScheduleTest {

    private static final String DEFAULT = "0s,30s,2m,5m,10m";

    private static RefundSchedule schedule(String text, double jitter) {
        return RefundSchedule.parse(text, jitter, new Random(7));
    }

    @Test
    void theDefaultScheduleHasFiveAttemptsAndAHorizonOfSeventeenAndAHalfMinutes() {
        RefundSchedule schedule = schedule(DEFAULT, RefundSchedule.JITTER);

        assertEquals(5, schedule.maxAttempts());
        assertEquals(List.of(Duration.ZERO, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10)), schedule.basePauses());
        assertEquals(Duration.ofSeconds(1050), schedule.horizon());
        assertTrue(schedule.maxHorizon().compareTo(Duration.ofMinutes(30)) < 0, "с разбросом автовозврат укладывается в 30 минут (NFT-2.4)");
        assertEquals(Duration.ofSeconds(1260), schedule.maxHorizon());
    }

    @Test
    void theFirstAttemptIsImmediateAndTheOthersWaitWithinTwentyPercent() {
        RefundSchedule schedule = schedule(DEFAULT, RefundSchedule.JITTER);
        Set<Duration> seen = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            assertEquals(Duration.ZERO, schedule.pauseBefore(1));
            Duration pause = schedule.pauseBefore(2);
            seen.add(pause);
            assertTrue(pause.compareTo(Duration.ofSeconds(24)) >= 0 && pause.compareTo(Duration.ofSeconds(36)) <= 0, "пауза вне ±20 %: " + pause);
            Duration last = schedule.pauseBefore(5);
            assertTrue(last.compareTo(Duration.ofMinutes(8)) >= 0 && last.compareTo(Duration.ofMinutes(12)) <= 0, "пауза вне ±20 %: " + last);
        }
        assertTrue(seen.size() > 100, "разброс есть: возвраты разных заказов не выстраиваются в одну секунду");
    }

    @Test
    void withoutJitterThePausesAreExact() {
        RefundSchedule schedule = schedule(DEFAULT, 0);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(schedule.basePauses().get(attempt - 1), schedule.pauseBefore(attempt));
        }
    }

    @Test
    void theDueTimeIsTheEndOfThePreviousAttemptPlusThePause() {
        RefundSchedule schedule = schedule("0s,30s,2m", 0);
        Instant end = Instant.parse("2026-10-18T10:00:00Z");

        assertEquals(end, schedule.dueAt(1, end));
        assertEquals(end.plusSeconds(30), schedule.dueAt(2, end));
        assertEquals(end.plusSeconds(120), schedule.dueAt(3, end));
    }

    @Test
    void theFastTimeScheduleAndMillisecondsAreParsed() {
        RefundSchedule schedule = schedule("0s, 3s,12s ,30s,1m", 0);
        assertEquals(List.of(Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(12), Duration.ofSeconds(30), Duration.ofMinutes(1)), schedule.basePauses());
        assertEquals(List.of(Duration.ZERO, Duration.ofMillis(250), Duration.ofHours(1)), schedule("0s,250ms,1h", 0).basePauses());
    }

    @Test
    void aShorterScheduleHasFewerAttempts() {
        assertEquals(1, schedule("0s", 0).maxAttempts());
        assertEquals(3, schedule("0s,1s,2s", 0).maxAttempts());
    }

    @Test
    void aBrokenScheduleIsRefusedWithAReason() {
        assertThrows(IllegalArgumentException.class, () -> schedule("", 0));
        assertThrows(IllegalArgumentException.class, () -> schedule("  ", 0));
        assertThrows(IllegalArgumentException.class, () -> RefundSchedule.parse(null, 0, new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> schedule("0s,30x", 0));
        assertThrows(IllegalArgumentException.class, () -> schedule("0s,-5s", 0));
        IllegalArgumentException six = assertThrows(IllegalArgumentException.class, () -> schedule("0s,1s,2s,3s,4s,5s", 0));
        assertTrue(six.getMessage().contains("6"), six.getMessage());
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class, () -> schedule("0s,0s,2s", 0));
        assertTrue(zero.getMessage().contains("2"), zero.getMessage());
    }

    @Test
    void theJitterMustBeAFractionBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> schedule(DEFAULT, -0.1));
        assertThrows(IllegalArgumentException.class, () -> schedule(DEFAULT, 1));
        assertThrows(NullPointerException.class, () -> RefundSchedule.of(null, 0, new Random(1)));
        assertThrows(NullPointerException.class, () -> RefundSchedule.of(List.of(Duration.ZERO), 0, null));
        assertThrows(IllegalArgumentException.class, () -> RefundSchedule.of(List.of(), 0, new Random(1)));
    }

    @Test
    void anAttemptOutsideTheScheduleIsAProgrammingError() {
        RefundSchedule schedule = schedule("0s,1s", 0);
        assertThrows(IndexOutOfBoundsException.class, () -> schedule.pauseBefore(0));
        assertThrows(IndexOutOfBoundsException.class, () -> schedule.pauseBefore(3));
    }

    @Test
    void anAttemptRecordKeepsItsNumberWithinTheLimitsOfTheDatabase() {
        java.util.UUID id = java.util.UUID.fromString("0199e0a0-0000-7000-8000-000000000501");
        java.util.UUID payment = java.util.UUID.fromString("0199e0a0-0000-7000-8000-000000000401");
        Instant due = Instant.parse("2026-10-18T10:00:00Z");

        RefundAttempt attempt = RefundAttempt.scheduled(id, payment, 1, due);
        assertTrue(attempt.open());
        assertEquals(RefundAttempt.PENDING, attempt.status());
        assertEquals(due, attempt.nextAttemptAt());
        assertFalse(new RefundAttempt(id, payment, 2, RefundAttempt.PROCESSING, null, null).open());
        assertFalse(new RefundAttempt(id, payment, 3, RefundAttempt.FAILED, null, "x").open());
        assertTrue(new RefundAttempt(id, payment, 5, RefundAttempt.IN_PROGRESS, due, null).open());
        assertThrows(IllegalArgumentException.class, () -> RefundAttempt.scheduled(id, payment, 0, due));
        assertThrows(IllegalArgumentException.class, () -> RefundAttempt.scheduled(id, payment, 6, due));
    }
}
