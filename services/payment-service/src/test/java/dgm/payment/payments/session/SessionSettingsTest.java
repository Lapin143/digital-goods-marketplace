package dgm.payment.payments.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.config.ConfigRegistry;
import dgm.kit.config.ConfigRegistry.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** INV-07 при старте сервиса: платёжная сессия короче резерва с запасом; адрес возврата строится от публичного адреса. */
@Tag("TC-501")
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

    // ------------------------------------------------------------------------------------------------ TC-808

    private static ConfigRegistry registry(String... pinned) {
        return new ConfigRegistry(List.of(pinned), new SimpleMeterRegistry());
    }

    @Tag("TC-808")
    @Test
    void liveSettingsFollowTheRegistryAndKeepTheMarginOfTheSessionBeforeTheEndOfTheReserve() {
        ConfigRegistry registry = registry();
        SessionSettings live = SessionSettings.live(registry, of(900, 720, 60));
        assertEquals(of(900, 720, 60), live, "пока сообщений нет, работают значения из application.yml");

        assertEquals(Outcome.APPLIED, registry.apply("reservation.ttl-seconds", 1800, 2));
        assertEquals(Outcome.APPLIED, registry.apply("payment-session.ttl-seconds", 1500, 2));
        assertEquals(Duration.ofSeconds(1800), live.reservationTtl());
        assertEquals(Duration.ofSeconds(1500), live.sessionTtl());

        assertEquals(Outcome.INVARIANT, registry.apply("payment-session.ttl-seconds", 1741, 3), "запас до конца резерва меньше 60 секунд");
        assertEquals(Outcome.INVARIANT, registry.apply("reservation.ttl-seconds", 1559, 3), "резерв съедает запас сессии");
        assertEquals(Outcome.APPLIED, registry.apply("payment-session.ttl-seconds", 1740, 3), "ровно с запасом 60 секунд");
        assertEquals(Duration.ofSeconds(1740), live.sessionTtl());
        assertEquals(Duration.ofSeconds(60), live.margin(), "запас и адрес не параметры платформы");
    }

    @Tag("TC-808")
    @Test
    void aKeyAlreadyRegisteredWithAnotherInitialValueStopsTheStart() {
        ConfigRegistry registry = registry();
        registry.register("reservation.ttl-seconds", 1000, 1, 86_400);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SessionSettings.live(registry, of(900, 720, 60)));
        assertTrue(e.getMessage().contains("reservation.ttl-seconds"), e.getMessage());
    }

    @Tag("TC-808")
    @Test
    void theSameKeysMayBeObtainedByAnotherModuleOfTheServiceWithTheSameInitialValues() {
        ConfigRegistry registry = registry();
        SessionSettings first = SessionSettings.live(registry, of(900, 720, 60));
        registry.obtain("reservation.ttl-seconds", 900, 1, 86_400);
        registry.apply("reservation.ttl-seconds", 1000, 2);
        assertEquals(Duration.ofSeconds(1000), first.reservationTtl());
    }

    @Tag("TC-808")
    @Test
    void pinnedKeysKeepTheFastTimeValues() {
        ConfigRegistry registry = registry("reservation.ttl-seconds", "payment-session.ttl-seconds");
        SessionSettings live = SessionSettings.live(registry, of(30, 24, 5));
        assertEquals(Outcome.PINNED, registry.apply("reservation.ttl-seconds", 900, 1));
        assertEquals(Outcome.PINNED, registry.apply("payment-session.ttl-seconds", 720, 1));
        assertEquals(Duration.ofSeconds(30), live.reservationTtl());
        assertEquals(Duration.ofSeconds(24), live.sessionTtl());
    }
}
