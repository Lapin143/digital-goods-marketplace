package dgm.order.orders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Настройки заказа проверяются при старте: INV-07 (сессия короче резерва), лимит неоплаченных не меньше единицы, комиссия в пределах. */
@Tag("TC-510")
class OrderSettingsTest {

    private static OrderSettings of(int maxUnpaid, long reservation, long session, int commission) {
        return new OrderSettings(maxUnpaid, Duration.ofSeconds(reservation), Duration.ofSeconds(session), commission);
    }

    @Test
    void theDefaultsOfThePlatformFit() {
        OrderSettings settings = of(3, 900, 720, 200);
        assertEquals(3, settings.maxUnpaid());
        assertEquals(Duration.ofMinutes(15), settings.reservationTtl());
        assertEquals(Duration.ofMinutes(12), settings.sessionTtl());
    }

    @Test
    void theFastTimeValuesFit() {
        of(3, 30, 24, 200);
    }

    @Test
    void aSessionAsLongAsTheReservationOrLongerStopsTheStartWithAClearReason() {
        IllegalStateException equal = assertThrows(IllegalStateException.class, () -> of(3, 900, 900, 200));
        assertTrue(equal.getMessage().contains("INV-07"), equal.getMessage());
        assertTrue(equal.getMessage().contains("900"), equal.getMessage());
        assertThrows(IllegalStateException.class, () -> of(3, 900, 1000, 200));
    }

    @Test
    void zeroAndNegativeValuesAreRefused() {
        assertThrows(IllegalStateException.class, () -> of(0, 900, 720, 200));
        assertThrows(IllegalStateException.class, () -> of(3, 0, 0, 200));
        assertThrows(IllegalStateException.class, () -> of(3, 900, 0, 200));
        assertThrows(IllegalStateException.class, () -> of(3, 900, -1, 200));
        assertThrows(IllegalStateException.class, () -> new OrderSettings(3, null, Duration.ofSeconds(10), 200));
        assertThrows(IllegalStateException.class, () -> new OrderSettings(3, Duration.ofSeconds(30), null, 200));
    }

    @Test
    void theCommissionIsFromZeroToTenThousandBasisPoints() {
        of(3, 900, 720, 0);
        of(3, 900, 720, 10_000);
        assertThrows(IllegalStateException.class, () -> of(3, 900, 720, -1));
        assertThrows(IllegalStateException.class, () -> of(3, 900, 720, 10_001));
    }
}
