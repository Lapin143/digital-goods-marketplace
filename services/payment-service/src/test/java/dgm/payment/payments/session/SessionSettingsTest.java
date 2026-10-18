package dgm.payment.payments.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** INV-07 при старте сервиса: платёжная сессия короче резерва с запасом; адрес возврата строится от публичного адреса. */
class SessionSettingsTest {

    private static final String ORIGIN = "https://localhost:8443";

    private static SessionSettings of(long reservation, long session, long margin) {
        return new SessionSettings(Duration.ofSeconds(reservation), Duration.ofSeconds(session), Duration.ofSeconds(margin), ORIGIN);
    }

    @Test
    void defaultsOfThePlatformFit() {
        SessionSettings settings = of(900, 720, 60);
        assertEquals(Duration.ofMinutes(12), settings.sessionTtl());
    }

    @Test
    void theFastTimeValuesFit() {
        of(30, 24, 5);
    }

    @Test
    void aSessionWithoutTheMarginStopsTheStartWithAClearReason() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> of(900, 850, 60));
        assertTrue(e.getMessage().contains("INV-07"), e.getMessage());
        assertTrue(e.getMessage().contains("850"), e.getMessage());
        assertTrue(e.getMessage().contains("900"), e.getMessage());
    }

    @Test
    void aSessionEqualToOrLongerThanTheReservationIsRefused() {
        assertThrows(IllegalStateException.class, () -> of(900, 900, 0));
        assertThrows(IllegalStateException.class, () -> of(900, 1000, 0));
    }

    @Test
    void zeroAndNegativeValuesAreRefused() {
        assertThrows(IllegalStateException.class, () -> of(0, 0, 0));
        assertThrows(IllegalStateException.class, () -> of(900, 0, 0));
        assertThrows(IllegalStateException.class, () -> of(900, 720, -1));
    }

    @Test
    void thePublicOriginIsAnAddressWithoutAPath() {
        assertThrows(IllegalStateException.class, () -> new SessionSettings(Duration.ofSeconds(900), Duration.ofSeconds(720), Duration.ofSeconds(60), "localhost"));
        assertThrows(IllegalStateException.class,
                () -> new SessionSettings(Duration.ofSeconds(900), Duration.ofSeconds(720), Duration.ofSeconds(60), "https://localhost:8443/"));
        assertThrows(IllegalStateException.class,
                () -> new SessionSettings(Duration.ofSeconds(900), Duration.ofSeconds(720), Duration.ofSeconds(60), "https://localhost:8443/shop"));
    }

    @Test
    void theReturnAddressPointsToTheOrderPageOfThePlatform() {
        UUID order = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
        assertEquals(ORIGIN + "/orders/" + order, of(900, 720, 60).returnUrl(order));
    }
}
