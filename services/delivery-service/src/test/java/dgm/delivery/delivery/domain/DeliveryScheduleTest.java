package dgm.delivery.delivery.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Расписание повторов выдачи (ADR-011, TC-601): паузы 10 с, 30 с, 2, 5, 10 минут после неудач, шесть попыток, разброс ±20 %, горизонт 17 минут
 * 40 секунд, разбор настройки и отказ на неверных значениях.
 */
@Tag("TC-601")
class DeliveryScheduleTest {

    private static final Instant T0 = Instant.parse("2026-10-19T12:00:00Z");

    private static DeliverySchedule defaults(double jitter) {
        return DeliverySchedule.parse("10s,30s,2m,5m,10m", jitter, new Random(7));
    }

    @Test
    void theDefaultScheduleHasFivePausesAndSixAttempts() {
        DeliverySchedule schedule = defaults(DeliverySchedule.JITTER);

        assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10)),
                schedule.basePauses());
        assertEquals(6, schedule.maxAttempts());
        assertEquals(Duration.ofMinutes(17).plusSeconds(40), schedule.horizon());
        assertTrue(schedule.horizon().compareTo(Duration.ofMinutes(30)) < 0, "шестая попытка укладывается в контроль 30 минут");
    }

    @Test
    void theFastTimeScheduleKeepsSixAttemptsAndShrinksThePauses() {
        DeliverySchedule schedule = DeliverySchedule.parse("1s,3s,12s,30s,60s", DeliverySchedule.JITTER, new Random(7));

        assertEquals(6, schedule.maxAttempts());
        assertEquals(Duration.ofSeconds(106), schedule.horizon());
    }

    @Test
    void everyPauseStaysWithinTwentyPercentOfItsBase() {
        DeliverySchedule schedule = defaults(DeliverySchedule.JITTER);

        for (int round = 0; round < 500; round++) {
            for (int failed = 1; failed <= 5; failed++) {
                long base = schedule.basePauses().get(failed - 1).toMillis();
                long pause = schedule.pauseAfter(failed).toMillis();
                assertTrue(pause >= base * 0.8 - 1 && pause <= base * 1.2 + 1, "пауза " + pause + " мс вне ±20 % от " + base);
            }
        }
    }

    @Test
    void theSpreadActuallySpreadsThePauses() {
        DeliverySchedule schedule = defaults(DeliverySchedule.JITTER);

        long distinct = java.util.stream.IntStream.range(0, 50).mapToObj(i -> schedule.pauseAfter(3)).distinct().count();

        assertTrue(distinct > 40, "пауза с разбросом должна различаться: " + distinct);
    }

    @Test
    void withoutSpreadThePausesAreExactAndTheNextAttemptIsCountedFromTheFailure() {
        DeliverySchedule schedule = defaults(0);

        assertEquals(Duration.ofSeconds(10), schedule.pauseAfter(1));
        assertEquals(T0.plusSeconds(30), schedule.dueAfter(2, T0));
        assertEquals(T0.plus(Duration.ofMinutes(10)), schedule.dueAfter(5, T0));
    }

    @Test
    void theQueueIsExhaustedOnlyAfterTheSixthFailure() {
        DeliverySchedule schedule = defaults(0);

        for (int failed = 1; failed <= 5; failed++) {
            assertFalse(schedule.exhaustedAfter(failed), "после неудачи " + failed + " есть ещё попытка");
        }
        assertTrue(schedule.exhaustedAfter(6));
        assertTrue(schedule.exhaustedAfter(7));
    }

    @Test
    void theMaximumHorizonAddsTheSpread() {
        assertEquals(Duration.ofSeconds(1272), defaults(DeliverySchedule.JITTER).maxHorizon());
    }

    @Test
    void thereIsNoPauseAfterTheLastAttemptOrBeforeTheFirst() {
        DeliverySchedule schedule = defaults(0);

        assertThrows(IndexOutOfBoundsException.class, () -> schedule.pauseAfter(0));
        assertThrows(IndexOutOfBoundsException.class, () -> schedule.pauseAfter(6));
    }

    @Test
    void unitsAreMillisecondsSecondsMinutesAndHours() {
        DeliverySchedule schedule = DeliverySchedule.parse("500ms, 2s ,3m,1h", 0, new Random(1));

        assertEquals(List.of(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofMinutes(3), Duration.ofHours(1)), schedule.basePauses());
        assertEquals(5, schedule.maxAttempts());
    }

    @Test
    void wrongSettingsAreRefused() {
        Random random = new Random(1);

        assertThrows(IllegalArgumentException.class, () -> DeliverySchedule.parse("", 0.2, random));
        assertThrows(IllegalArgumentException.class, () -> DeliverySchedule.parse("   ", 0.2, random));
        assertThrows(IllegalArgumentException.class, () -> DeliverySchedule.parse("10", 0.2, random));
        assertThrows(IllegalArgumentException.class, () -> DeliverySchedule.parse("10x", 0.2, random));
        assertThrows(IllegalArgumentException.class, () -> DeliverySchedule.parse("0s,10s", 0.2, random), "нулевая пауза не допускается");
        assertThrows(IllegalArgumentException.class, () -> DeliverySchedule.parse("1s,2s,3s,4s,5s,6s", 0.2, random), "шесть пауз это семь попыток, таблица держит шесть");
        assertThrows(IllegalArgumentException.class, () -> DeliverySchedule.parse("10s", -0.1, random));
        assertThrows(IllegalArgumentException.class, () -> DeliverySchedule.parse("10s", 1, random));
        assertThrows(IllegalArgumentException.class, () -> DeliverySchedule.of(List.of(Duration.ofSeconds(-1)), 0.2, random));
        assertThrows(NullPointerException.class, () -> DeliverySchedule.of(null, 0.2, random));
    }
}
