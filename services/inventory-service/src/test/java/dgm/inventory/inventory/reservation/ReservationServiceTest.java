package dgm.inventory.inventory.reservation;

import static dgm.inventory.inventory.reservation.FakeStock.PRODUCT;
import static dgm.inventory.inventory.reservation.FakeStock.SELLER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.registry.ProductCopy;
import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.inventory.inventory.reservation.domain.ReservationSettings;
import dgm.inventory.inventory.reservation.service.ReservationService;
import dgm.inventory.inventory.reservation.service.ReservationService.Detected;
import dgm.inventory.inventory.reservation.service.ReservationService.Expiry;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Резерв ключей без базы и Redis (SM-08, SM-03, INV-05, INV-06, ADR-007, ADR-012): создание, срок от часов, недостаток ключей,
 * истечение, снятие по отмене, версии остатка и события. Гонки и настоящий SQL проверяют интеграционные тесты.
 */
class ReservationServiceTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final Duration TTL = Duration.ofMinutes(15);

    private FakeStock stock;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        stock = new FakeStock(new ReservationSettings(TTL, Duration.ofSeconds(30)));
        stock.keys.load(PRODUCT, 5);
        service = stock.service;
    }

    private static ProblemException refused(Runnable call) {
        return assertThrows(ProblemException.class, call::run);
    }

    @Test
    void aReservationOfThreeOutOfFiveHoldsThreeKeysAndExpiresAfterTheConfiguredTtl() {
        Reservation reservation = service.reserve(ORDER, PRODUCT, 3);
        assertEquals(Reservation.ACTIVE, reservation.status());
        assertEquals(3, reservation.quantity());
        assertEquals(FakeStock.START.plus(TTL), reservation.expiresAt(), "срок это время часов плюс параметр, а не число в коде");
        assertEquals(3, stock.keys.held(reservation.id()));
        assertEquals(2, stock.keys.free(PRODUCT));
        assertEquals(Map.of(reservation.id(), reservation.expiresAt()), stock.queue.scheduled, "таймер поставлен после фиксации");
    }

    @Test
    void theTtlComesFromTheSettingsSoTheFastProfileChangesOnlyConfiguration() {
        FakeStock fast = new FakeStock(new ReservationSettings(Duration.ofSeconds(30), Duration.ofSeconds(5)));
        fast.keys.load(PRODUCT, 1);
        Reservation reservation = fast.service.reserve(ORDER, PRODUCT, 1);
        assertEquals(FakeStock.START.plusSeconds(30), reservation.expiresAt());
    }

    @Test
    void reservingMoreThanIsFreeAnswers409WithTheAvailableCountAndChangesNothing() {
        ProblemException e = refused(() -> service.reserve(ORDER, PRODUCT, 6));
        assertEquals(ProblemType.INSUFFICIENT_STOCK, e.type());
        assertEquals(5, e.extensions().get("available"));
        assertTrue(stock.reservations.rows.isEmpty(), "частичного резерва нет (INV-05)");
        assertEquals(5, stock.keys.free(PRODUCT));
        assertTrue(stock.transaction.events.isEmpty());
        assertEquals(0L, stock.version());
        assertTrue(stock.queue.scheduled.isEmpty());
    }

    @Test
    void theProductRowIsLockedBeforeAnythingElse() {
        service.reserve(ORDER, PRODUCT, 1);
        assertEquals(java.util.List.of(PRODUCT), stock.products.locked);
    }

    @Test
    void anOrderHasOneActiveReservationAndAReleasedOneAllowsANewAttempt() {
        Reservation first = service.reserve(ORDER, PRODUCT, 2);
        ProblemException e = refused(() -> service.reserve(ORDER, PRODUCT, 2));
        assertEquals(ProblemType.STATE_CONFLICT, e.type(), "INV-06");
        assertEquals(Reservation.ACTIVE, e.extensions().get("currentStatus"));
        assertEquals(1, stock.reservations.rows.size());
        assertEquals(3, stock.keys.free(PRODUCT), "отказ ничего не удержал");

        service.release(ORDER, Reservation.ORDER_CANCELLED);
        Reservation second = service.reserve(ORDER, PRODUCT, 2);
        assertFalse(first.id().equals(second.id()));
        assertEquals(Reservation.ACTIVE, second.status());
    }

    @Test
    void aUsedReservationBlocksANewOneForTheSameOrder() {
        Reservation reservation = service.reserve(ORDER, PRODUCT, 1);
        stock.reservations.forceUsed(reservation.id());
        ProblemException e = refused(() -> service.reserve(ORDER, PRODUCT, 1));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals(Reservation.USED, e.extensions().get("currentStatus"));
    }

    @Test
    void anUnknownUnpublishedOrNonPoolProductIsRefused() {
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.reserve(ORDER, UUID.randomUUID(), 1)).type());
        stock.products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "key_pool", "blocked", 1));
        ProblemException blocked = refused(() -> service.reserve(ORDER, PRODUCT, 1));
        assertEquals(ProblemType.PRODUCT_UNAVAILABLE, blocked.type());
        assertEquals("blocked", blocked.extensions().get("currentStatus"));
        stock.products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "seller_api", "published", 1));
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> service.reserve(ORDER, PRODUCT, 1)).type());
        assertTrue(stock.reservations.rows.isEmpty());
    }

    @Test
    void reservingWritesStockChangedWithTheNewAvailableCountAndVersionOne() {
        service.reserve(ORDER, PRODUCT, 3);
        assertEquals(1, stock.transaction.events.size());
        FakeStock.Tx.Event event = stock.transaction.events.get(0);
        assertEquals("inventory.events", event.topic());
        assertEquals("ProductStock", event.aggregateType());
        assertEquals(PRODUCT.toString(), event.aggregateId());
        assertEquals("stock.changed", event.type());
        assertEquals(Map.of("productId", PRODUCT.toString(), "available", 2, "version", 1L), event.data());
    }

    @Test
    void anExpiryBeforeTheDeadlineChangesNothing() {
        Reservation reservation = service.reserve(ORDER, PRODUCT, 3);
        stock.clock.advance(TTL.minusSeconds(1));
        assertEquals(Expiry.NOT_DUE, service.expire(reservation.id(), Detected.TIMER));
        assertEquals(Reservation.ACTIVE, stock.reservations.rows.get(reservation.id()).status());
        assertEquals(2, stock.keys.free(PRODUCT));
        assertEquals(1L, stock.version());
        assertTrue(stock.queue.scheduled.containsKey(reservation.id()), "запись очереди остаётся до срока");
    }

    @Test
    void anExpiryAtTheDeadlineReleasesTheKeysAndWritesBothEvents() {
        Reservation reservation = service.reserve(ORDER, PRODUCT, 3);
        stock.clock.advance(TTL);
        assertEquals(Expiry.EXPIRED, service.expire(reservation.id(), Detected.TIMER));

        Reservation after = stock.reservations.rows.get(reservation.id());
        assertEquals(Reservation.RELEASED, after.status());
        assertEquals(Reservation.EXPIRED, after.releaseReason());
        assertEquals(5, stock.keys.free(PRODUCT), "SM-03/T3: ключи снова свободны");
        assertEquals(0, stock.keys.held(reservation.id()));
        assertTrue(stock.queue.scheduled.isEmpty(), "участник убран из очереди");

        FakeStock.Tx.Event expired = stock.events("reservation.expired").get(0);
        assertEquals("Reservation", expired.aggregateType());
        assertEquals(ORDER.toString(), expired.aggregateId(), "ключ записи Kafka это идентификатор заказа");
        assertEquals(Map.of("reservationId", reservation.id().toString(), "orderId", ORDER.toString(), "productId", PRODUCT.toString(),
                "quantity", 3, "expiredBy", "timer"), expired.data());
        FakeStock.Tx.Event changed = stock.events("stock.changed").get(1);
        assertEquals(5, changed.data().get("available"));
        assertEquals(2L, changed.data().get("version"));
    }

    @Test
    void theSecondExpiryOfTheSameReservationIsASignalNotATransition() {
        Reservation reservation = service.reserve(ORDER, PRODUCT, 3);
        stock.clock.advance(TTL);
        assertEquals(Expiry.EXPIRED, service.expire(reservation.id(), Detected.TIMER));
        int events = stock.transaction.events.size();
        assertEquals(Expiry.GONE, service.expire(reservation.id(), Detected.RECONCILIATION));
        assertEquals(events, stock.transaction.events.size(), "повтор не пишет событий (правило 11)");
        assertEquals(2L, stock.version());
        assertEquals(Expiry.GONE, service.expire(UUID.randomUUID(), Detected.TIMER), "неизвестный резерв тоже не ошибка");
    }

    @Test
    void anExpiryDetectedByTheReconcilerIsMarkedAsSuch() {
        Reservation reservation = service.reserve(ORDER, PRODUCT, 1);
        stock.clock.advance(TTL.plusSeconds(31));
        assertEquals(Expiry.EXPIRED, service.expire(reservation.id(), Detected.RECONCILIATION));
        assertEquals("reconciliation", stock.events("reservation.expired").get(0).data().get("expiredBy"));
        assertEquals(1.0, stock.meters.get("reservations_expired_total").tag("by", "reconciliation").counter().count());
    }

    @Test
    void anUsedReservationIsNeverExpiredOrReleased() {
        Reservation reservation = service.reserve(ORDER, PRODUCT, 2);
        stock.reservations.forceUsed(reservation.id());
        stock.clock.advance(TTL.plusHours(1));
        assertEquals(Expiry.GONE, service.expire(reservation.id(), Detected.TIMER));
        assertEquals(Optional.empty(), service.release(ORDER, Reservation.ORDER_CANCELLED));
        assertEquals(Reservation.USED, stock.reservations.rows.get(reservation.id()).status());
        assertEquals(2, stock.keys.held(reservation.id()), "ключи использованного резерва остаются за заказом");
    }

    @Test
    void cancellationReleasesTheActiveReservationWithTheGivenReason() {
        Reservation reservation = service.reserve(ORDER, PRODUCT, 3);
        Optional<Reservation> released = service.release(ORDER, Reservation.PAYMENT_DECLINED);
        assertTrue(released.isPresent());
        assertEquals(Reservation.PAYMENT_DECLINED, stock.reservations.rows.get(reservation.id()).releaseReason());
        assertEquals(5, stock.keys.free(PRODUCT));
        FakeStock.Tx.Event event = stock.events("reservation.released").get(0);
        assertEquals(Map.of("reservationId", reservation.id().toString(), "orderId", ORDER.toString(), "productId", PRODUCT.toString(),
                "quantity", 3, "reason", "payment_declined"), event.data());
        assertTrue(stock.queue.scheduled.isEmpty());
    }

    @Test
    void cancellingTwiceOrAfterExpiryIsHarmless() {
        Reservation reservation = service.reserve(ORDER, PRODUCT, 3);
        assertTrue(service.release(ORDER, Reservation.ORDER_CANCELLED).isPresent());
        int events = stock.transaction.events.size();
        assertEquals(Optional.empty(), service.release(ORDER, Reservation.ORDER_CANCELLED), "повторная отмена не имеет эффекта (INV-06)");
        assertEquals(events, stock.transaction.events.size());

        UUID other = UUID.randomUUID();
        Reservation expiring = service.reserve(other, PRODUCT, 1);
        stock.clock.advance(TTL);
        service.expire(expiring.id(), Detected.TIMER);
        assertEquals(Optional.empty(), service.release(other, Reservation.ORDER_CANCELLED), "отмена после истечения срока ничего не меняет");
        assertEquals(Reservation.EXPIRED, stock.reservations.rows.get(expiring.id()).releaseReason());
        assertEquals(Reservation.ORDER_CANCELLED, stock.reservations.rows.get(reservation.id()).releaseReason());
    }

    @Test
    void stockVersionsGrowByOneWithoutGapsAcrossReserveExpireAndRelease() {
        Reservation first = service.reserve(ORDER, PRODUCT, 2);
        UUID other = UUID.randomUUID();
        service.reserve(other, PRODUCT, 1);
        stock.clock.advance(TTL);
        service.expire(first.id(), Detected.TIMER);
        service.release(other, Reservation.ORDER_CANCELLED);
        assertEquals(java.util.List.of(1L, 2L, 3L, 4L), stock.events("stock.changed").stream().map(e -> e.data().get("version")).toList());
        assertEquals(java.util.List.of(3, 2, 4, 5), stock.events("stock.changed").stream().map(e -> e.data().get("available")).toList());
    }

    @Test
    void anUnavailableRedisDoesNotBreakTheReservationAndIsCounted() {
        stock.queue.failing = true;
        Reservation reservation = service.reserve(ORDER, PRODUCT, 1);
        assertEquals(Reservation.ACTIVE, reservation.status(), "источник истины база, Redis лишь запускает снятие");
        assertEquals(1.0, stock.meters.get("reservation_timer_errors_total").tag("op", "schedule").counter().count());
        stock.clock.advance(TTL);
        assertEquals(Expiry.EXPIRED, service.expire(reservation.id(), Detected.RECONCILIATION), "сверка снимает резерв без таймера");
        assertEquals(1.0, stock.meters.get("reservation_timer_errors_total").tag("op", "remove").counter().count());
    }

    @Test
    void settingsRejectValuesOutsideTheBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ReservationSettings(Duration.ZERO, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new ReservationSettings(Duration.ofDays(2), Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new ReservationSettings(TTL, Duration.ofSeconds(-1)));
        assertEquals(Duration.ofMinutes(15), ReservationSettings.defaults().ttl());
    }
}
