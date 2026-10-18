package dgm.inventory.inventory.reservation;

import static dgm.inventory.inventory.reservation.FakeStock.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.inventory.inventory.reservation.domain.ReservationSettings;
import dgm.inventory.inventory.reservation.service.ReservationService;
import dgm.inventory.inventory.reservation.service.ReservationService.Confirmation;
import dgm.inventory.inventory.reservation.service.ReservationService.Detected;
import dgm.inventory.inventory.reservation.service.ReservationService.Expiry;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Подтверждение резерва после оплаты (SM-08/T2, SM-03/T4, ADR-013, SEQ-02, INV-04, INV-05, INV-06): активный резерв, повтор, поздняя
 * оплата с повторным резервом, нехватка ключей, расхождение с резервом заказа, оплата на границе срока. Настоящие гонки и SQL
 * проверяют интеграционные тесты.
 */
class ReservationConfirmationTest {

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
    void anActiveReservationBecomesUsedAndItsKeysAreIssued() {
        Reservation reserved = service.reserve(ORDER, PRODUCT, 3);
        Confirmation confirmation = service.confirm(ORDER, PRODUCT, 3);
        assertFalse(confirmation.reReserved());
        assertEquals(reserved.id(), confirmation.reservation().id(), "подтверждается тот же резерв");
        assertEquals(Reservation.USED, confirmation.reservation().status());
        assertEquals(Reservation.USED, stock.reservations.rows.get(reserved.id()).status());
        assertTrue(stock.keys.isIssued(reserved.id()), "ключи выданы и остаются за заказом (SM-03/T4)");
        assertEquals(3, stock.keys.held(reserved.id()));
        assertEquals(2, stock.keys.free(PRODUCT));
        assertTrue(stock.queue.scheduled.isEmpty(), "таймер больше не нужен");
    }

    @Test
    void confirmationWritesStockChangedWithTheNextVersionAndUnchangedAvailability() {
        service.reserve(ORDER, PRODUCT, 3);
        service.confirm(ORDER, PRODUCT, 3);
        List<FakeStock.Tx.Event> events = stock.events("stock.changed");
        assertEquals(2, events.size());
        assertEquals(Map.of("productId", PRODUCT.toString(), "available", 2, "version", 2L), events.get(1).data());
        assertEquals(2L, stock.version());
    }

    @Test
    void theProductRowIsLockedBeforeTheReservationIsRead() {
        service.reserve(ORDER, PRODUCT, 1);
        stock.products.locked.clear();
        service.confirm(ORDER, PRODUCT, 1);
        assertEquals(List.of(PRODUCT), stock.products.locked, "порядок блокировок тот же, что у резерва, истечения и снятия");
    }

    @Test
    void aRepeatedConfirmationReturnsTheSameReservationAndChangesNothing() {
        service.reserve(ORDER, PRODUCT, 3);
        Confirmation first = service.confirm(ORDER, PRODUCT, 3);
        int events = stock.transaction.events.size();
        long version = stock.version();
        Confirmation again = service.confirm(ORDER, PRODUCT, 3);
        assertEquals(first.reservation(), again.reservation());
        assertFalse(again.reReserved());
        assertEquals(events, stock.transaction.events.size());
        assertEquals(version, stock.version());
        assertEquals(1, stock.reservations.rows.size());
        assertEquals(2, stock.keys.free(PRODUCT));
    }

    @Test
    void paymentAfterTheReservationExpiredTakesFreeKeysAndCreatesANewUsedReservation() {
        Reservation expired = service.reserve(ORDER, PRODUCT, 3);
        stock.clock.advance(TTL);
        assertEquals(Expiry.EXPIRED, service.expire(expired.id(), Detected.TIMER));
        assertEquals(5, stock.keys.free(PRODUCT));

        Confirmation confirmation = service.confirm(ORDER, PRODUCT, 3);
        assertTrue(confirmation.reReserved(), "поздняя оплата: резерв создан заново (SEQ-02)");
        assertNotEquals(expired.id(), confirmation.reservation().id());
        assertEquals(Reservation.USED, confirmation.reservation().status());
        assertEquals(3, confirmation.reservation().quantity());
        assertEquals(Reservation.EXPIRED, stock.reservations.rows.get(expired.id()).releaseReason(), "прежний резерв остаётся снятым");
        assertTrue(stock.keys.isIssued(confirmation.reservation().id()));
        assertEquals(3, stock.keys.held(confirmation.reservation().id()));
        assertEquals(2, stock.keys.free(PRODUCT));
        assertTrue(stock.queue.scheduled.isEmpty(), "использованный резерв не ставится на таймер");
        assertEquals(List.of(2, 5, 2), stock.events("stock.changed").stream().map(e -> e.data().get("available")).toList());
        assertEquals(List.of(1L, 2L, 3L), stock.events("stock.changed").stream().map(e -> e.data().get("version")).toList());

        // повтор после поздней оплаты возвращает новый резерв, а не создаёт третий
        Confirmation again = service.confirm(ORDER, PRODUCT, 3);
        assertEquals(confirmation.reservation().id(), again.reservation().id());
        assertEquals(2, stock.reservations.rows.size());
        assertEquals(2, stock.keys.free(PRODUCT));
    }

    @Test
    void paymentForACancelledOrderIsTreatedAsLatePayment() {
        Reservation reserved = service.reserve(ORDER, PRODUCT, 2);
        service.release(ORDER, Reservation.ORDER_CANCELLED);
        Confirmation confirmation = service.confirm(ORDER, PRODUCT, 2);
        assertTrue(confirmation.reReserved());
        assertNotEquals(reserved.id(), confirmation.reservation().id());
        assertEquals(3, stock.keys.free(PRODUCT));
    }

    @Test
    void latePaymentWithTooFewFreeKeysAnswers409AndCreatesNothing() {
        Reservation expired = service.reserve(ORDER, PRODUCT, 3);
        stock.clock.advance(TTL);
        service.expire(expired.id(), Detected.RECONCILIATION);
        UUID other = UUID.randomUUID();
        service.reserve(other, PRODUCT, 4);
        int events = stock.transaction.events.size();
        long version = stock.version();

        ProblemException e = refused(() -> service.confirm(ORDER, PRODUCT, 3));
        assertEquals(ProblemType.INSUFFICIENT_STOCK, e.type());
        assertEquals(1, e.extensions().get("available"));
        assertEquals(2, stock.reservations.rows.size(), "нового резерва нет (INV-05)");
        assertEquals(1, stock.keys.free(PRODUCT));
        assertEquals(events, stock.transaction.events.size());
        assertEquals(version, stock.version());
    }

    @Test
    void anOrderThatNeverHadAReservationOrAnUnknownProductIs404() {
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.confirm(ORDER, PRODUCT, 1)).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.confirm(ORDER, UUID.randomUUID(), 1)).type());
        assertTrue(stock.reservations.rows.isEmpty());
        assertTrue(stock.transaction.events.isEmpty());
    }

    @Test
    void aProductOrQuantityThatDiffersFromTheOrderReservationIsRefused() {
        service.reserve(ORDER, PRODUCT, 3);
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> service.confirm(ORDER, PRODUCT, 2)).type());
        UUID other = UUID.fromString("0199e0a0-0000-7000-8000-000000000202");
        stock.products.rows.put(other, new dgm.inventory.inventory.registry.ProductCopy(other, FakeStock.SELLER, "key_pool", "published", 1));
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> service.confirm(ORDER, other, 3)).type());
        assertEquals(Reservation.ACTIVE, stock.reservations.rows.values().iterator().next().status(), "отказ резерв не меняет");
        assertFalse(stock.keys.isIssued(stock.reservations.rows.values().iterator().next().id()));
    }

    @Test
    void paymentAtTheDeadlineBeforeTheTimerFiredKeepsTheKeysAndTheExpiryThenDoesNothing() {
        Reservation reserved = service.reserve(ORDER, PRODUCT, 3);
        stock.clock.advance(TTL);   // срок наступил, таймер ещё не сработал
        Confirmation confirmation = service.confirm(ORDER, PRODUCT, 3);
        assertFalse(confirmation.reReserved());
        assertEquals(reserved.id(), confirmation.reservation().id());
        assertEquals(Expiry.GONE, service.expire(reserved.id(), Detected.TIMER), "истечение после подтверждения ничего не меняет");
        assertEquals(Reservation.USED, stock.reservations.rows.get(reserved.id()).status());
        assertTrue(stock.keys.isIssued(reserved.id()));
        assertEquals(2, stock.keys.free(PRODUCT));
        assertTrue(stock.events("reservation.expired").isEmpty());
    }

    @Test
    void expiryFirstThenPaymentGivesExactlyOneActiveOrUsedReservationPerOrder() {
        Reservation reserved = service.reserve(ORDER, PRODUCT, 3);
        stock.clock.advance(TTL);
        assertEquals(Expiry.EXPIRED, service.expire(reserved.id(), Detected.TIMER));
        service.confirm(ORDER, PRODUCT, 3);
        long live = stock.reservations.rows.values().stream().filter(r -> r.isActive() || r.isUsed()).count();
        assertEquals(1, live, "INV-06");
        assertEquals(1L, stock.events("reservation.expired").size());
    }

    @Test
    void anIssuedKeyIsNeverReturnedToTheFreePool() {
        Reservation reserved = service.reserve(ORDER, PRODUCT, 3);
        service.confirm(ORDER, PRODUCT, 3);
        assertEquals(java.util.Optional.empty(), service.release(ORDER, Reservation.ORDER_CANCELLED), "использованный резерв не снимается");
        assertEquals(0, stock.keys.releaseByReservation(reserved.id()), "выданные ключи в свободные не возвращаются (INV-04)");
        assertEquals(2, stock.keys.free(PRODUCT));
    }
}
