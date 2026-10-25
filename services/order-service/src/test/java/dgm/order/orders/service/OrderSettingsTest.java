package dgm.order.orders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.config.ConfigRegistry;
import dgm.kit.config.ConfigRegistry.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
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

    // ------------------------------------------------------------------------------------------------ TC-808

    private static ConfigRegistry registry(String... pinned) {
        return new ConfigRegistry(List.of(pinned), new SimpleMeterRegistry());
    }

    @Tag("TC-808")
    @Test
    void liveSettingsStartFromTheValuesOfTheSettingsAndFollowTheRegistry() {
        ConfigRegistry registry = registry();
        OrderSettings live = OrderSettings.live(registry, of(3, 900, 720, 200));
        assertEquals(of(3, 900, 720, 200), live, "пока сообщений нет, работают значения из application.yml");

        registry.apply("orders.max-unpaid", 5, 2);
        registry.apply("commission.default-rate-bp", 300, 2);
        registry.apply("reservation.ttl-seconds", 1800, 2);
        registry.apply("payment-session.ttl-seconds", 1500, 2);

        assertEquals(5, live.maxUnpaid());
        assertEquals(300, live.commissionRateBp());
        assertEquals(Duration.ofSeconds(1800), live.reservationTtl());
        assertEquals(Duration.ofSeconds(1500), live.sessionTtl());
        assertEquals(of(5, 1800, 1500, 300), live);
    }

    @Tag("TC-808")
    @Test
    void liveSettingsKeepTheSessionStrictlyShorterThanTheReserveWhenMessagesArrive() {
        ConfigRegistry registry = registry();
        OrderSettings live = OrderSettings.live(registry, of(3, 900, 720, 200));
        registry.apply("reservation.ttl-seconds", 1000, 2);
        registry.apply("payment-session.ttl-seconds", 600, 2);

        assertEquals(Outcome.INVARIANT, registry.apply("payment-session.ttl-seconds", 1000, 3));
        assertEquals(Outcome.INVARIANT, registry.apply("reservation.ttl-seconds", 600, 3));

        assertEquals(Duration.ofSeconds(1000), live.reservationTtl());
        assertEquals(Duration.ofSeconds(600), live.sessionTtl());
    }

    @Tag("TC-808")
    @Test
    void aPinnedKeyKeepsTheValueOfTheSettings() {
        ConfigRegistry registry = registry("reservation.ttl-seconds", "payment-session.ttl-seconds");
        OrderSettings live = OrderSettings.live(registry, of(3, 30, 24, 200));

        assertEquals(Outcome.PINNED, registry.apply("reservation.ttl-seconds", 900, 1));
        assertEquals(Outcome.PINNED, registry.apply("payment-session.ttl-seconds", 720, 1));
        assertEquals(Outcome.APPLIED, registry.apply("orders.max-unpaid", 4, 1), "остальные ключи профиль не закрепляет");

        assertEquals(Duration.ofSeconds(30), live.reservationTtl());
        assertEquals(Duration.ofSeconds(24), live.sessionTtl());
        assertEquals(4, live.maxUnpaid());
    }

    @Tag("TC-808")
    @Test
    void serviceBoundsAreWiderThanThoseOfThePlatformBecauseFastTimeUsesSeconds() {
        ConfigRegistry registry = registry();
        OrderSettings live = OrderSettings.live(registry, of(3, 30, 24, 200));
        assertEquals(Duration.ofSeconds(30), live.reservationTtl(), "платформа не допустила бы 30 секунд, сервис допускает");
        assertEquals(Outcome.OUT_OF_RANGE, registry.apply("reservation.ttl-seconds", 86_401, 2));
        assertEquals(Outcome.OUT_OF_RANGE, registry.apply("orders.max-unpaid", 0, 2));
        assertEquals(Outcome.OUT_OF_RANGE, registry.apply("commission.default-rate-bp", 10_001, 2));
    }
}
