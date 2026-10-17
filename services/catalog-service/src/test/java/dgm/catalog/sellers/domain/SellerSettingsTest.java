package dgm.catalog.sellers.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Параметры сроков: значения по умолчанию из требований и границы параметров платформы. */
class SellerSettingsTest {

    private static SellerSettings of(long pause, long review, long ttl) {
        return new SellerSettings(Duration.ofSeconds(pause), Duration.ofSeconds(review), Duration.ofSeconds(ttl));
    }

    @Test
    void defaultsAreTwentyFourHoursThreeDaysAndFiveMinutes() {
        SellerSettings defaults = SellerSettings.defaults();
        assertEquals(Duration.ofHours(24), defaults.reapplicationPause());
        assertEquals(Duration.ofDays(3), defaults.reviewPeriod());
        assertEquals(Duration.ofMinutes(5), defaults.documentLinkTtl());
    }

    @Test
    void pauseIsBetweenAnHourAndAWeek() {
        of(3600, 259_200, 300);
        of(604_800, 259_200, 300);
        assertThrows(IllegalArgumentException.class, () -> of(3599, 259_200, 300));
        assertThrows(IllegalArgumentException.class, () -> of(604_801, 259_200, 300));
    }

    @Test
    void reviewPeriodMayBeShortOnlyForTheFastTimeStand() {
        of(86_400, 1, 300);
        of(86_400, 10, 300);
        of(86_400, 1_209_600, 300);
        assertThrows(IllegalArgumentException.class, () -> of(86_400, 0, 300));
        assertThrows(IllegalArgumentException.class, () -> of(86_400, 1_209_601, 300));
    }

    @Test
    void linkTtlIsBetweenAMinuteAndFifteenMinutes() {
        of(86_400, 259_200, 60);
        of(86_400, 259_200, 900);
        assertThrows(IllegalArgumentException.class, () -> of(86_400, 259_200, 59));
        assertThrows(IllegalArgumentException.class, () -> of(86_400, 259_200, 901));
    }

    @Test
    void messageNamesTheKey() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> of(60, 259_200, 300));
        assertEquals(true, e.getMessage().contains("seller.reapplication-pause-seconds"), e.getMessage());
    }
}
