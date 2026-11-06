package dgm.inventory.inventory.reservation;

import static dgm.inventory.inventory.reservation.FakeStock.API_PRODUCT;
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
import dgm.inventory.inventory.reservation.service.ReservationService.Confirmation;
import dgm.inventory.inventory.reservation.service.ReservationService.Detected;
import dgm.inventory.inventory.reservation.service.ReservationService.Expiry;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Резерв товара с выдачей по API по количеству без базы (R2, Р-183, Р-192, ADR-008, SM-08): вычитание остатка и отказ при нехватке (INV-09),
 * подтверждение без ключей, возврат количества при снятии ровно один раз, поздняя оплата, отмена после оплаты (T5) и то, что выданные
 * ключи пула в оборот не возвращаются (INV-04). Настоящий SQL, триггеры и гонки проверяют интеграционные тесты {@code ApiStockIT}.
 */
class ReservationApiStockTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID OTHER_ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000302");
    private static final Duration TTL = Duration.ofMinutes(15);

    private FakeStock stock;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        stock = new FakeStock(new ReservationSettings(TTL, Duration.ofSeconds(30)));
        stock.apiQuantity(10);
        service = stock.service;
    }

    private static ProblemException refused(Runnable call) {
        return assertThrows(ProblemException.class, call::run);
    }

    private int quantity() {
        return stock.apiStock.quantity(API_PRODUCT);
    }

    private List<Map<String, Object>> stockEvents() {
        return stock.events("stock.changed").stream().map(FakeStock.Tx.Event::data).toList();
    }

    @Tag("TC-416")
    @Test
    void aReservationSubtractsTheQuantityHoldsNoKeysAndExpiresAfterTheConfiguredTtl() {
        Reservation reservation = service.reserve(ORDER, API_PRODUCT, 3);
        assertEquals(Reservation.ACTIVE, reservation.status());
        assertEquals(3, reservation.quantity());
        assertEquals(FakeStock.START.plus(TTL), reservation.expiresAt());
        assertEquals(7, quantity());
        assertEquals(0, stock.keys.held(reservation.id()), "ключей у резерва по API нет");
        assertEquals(Map.of(reservation.id(), reservation.expiresAt()), stock.queue.scheduled, "таймер и сверка работают как для пула");
        assertEquals(List.of(Map.of("productId", API_PRODUCT.toString(), "available", 7, "version", 1L)), stockEvents());
        assertEquals(2, stock.apiStock.version(API_PRODUCT), "версия записи остатка растёт на единицу при каждом изменении");
    }

    @Tag("TC-416")
    @Test
    void notEnoughStockRefusesWithTheCurrentRemainderAndChangesNothing() {
        service.reserve(ORDER, API_PRODUCT, 3);
        int versionBefore = stock.apiStock.version(API_PRODUCT);
        ProblemException e = refused(() -> service.reserve(OTHER_ORDER, API_PRODUCT, 8));
        assertEquals(ProblemType.INSUFFICIENT_STOCK, e.type());
        assertEquals(7, e.extensions().get("available"));
        assertEquals(7, quantity());
        assertEquals(versionBefore, stock.apiStock.version(API_PRODUCT));
        assertEquals(1, stock.reservations.rows.size(), "отказ резерв не создаёт (INV-05)");
        assertEquals(1, stockEvents().size(), "и событие остатка не пишет");
    }

    @Tag("TC-416")
    @Test
    void theLastUnitsAreSoldAndTheNextOrderFindsZero() {
        service.reserve(ORDER, API_PRODUCT, 10);
        assertEquals(0, quantity());
        ProblemException e = refused(() -> service.reserve(OTHER_ORDER, API_PRODUCT, 1));
        assertEquals(ProblemType.INSUFFICIENT_STOCK, e.type());
        assertEquals(0, e.extensions().get("available"));
        assertEquals(0, quantity(), "остаток не уходит в минус (INV-09)");
    }

    @Tag("TC-416")
    @Test
    void aProductWhoseSellerHasNotSetTheStockYetCannotBeOrdered() {
        stock.apiStock.rows.clear();
        ProblemException e = refused(() -> service.reserve(ORDER, API_PRODUCT, 1));
        assertEquals(ProblemType.INSUFFICIENT_STOCK, e.type());
        assertEquals(0, e.extensions().get("available"));
        assertTrue(stock.reservations.rows.isEmpty());
    }

    @Tag("TC-416")
    @Test
    void anUnpublishedApiProductAndASecondActiveReservationOfTheOrderAreRefused() {
        stock.products.rows.put(API_PRODUCT, new ProductCopy(API_PRODUCT, SELLER, "seller_api", "blocked", 2));
        ProblemException blocked = refused(() -> service.reserve(ORDER, API_PRODUCT, 1));
        assertEquals(ProblemType.PRODUCT_UNAVAILABLE, blocked.type());
        assertEquals(10, quantity());

        stock.products.rows.put(API_PRODUCT, new ProductCopy(API_PRODUCT, SELLER, "seller_api", "published", 3));
        service.reserve(ORDER, API_PRODUCT, 2);
        ProblemException again = refused(() -> service.reserve(ORDER, API_PRODUCT, 2));
        assertEquals(ProblemType.STATE_CONFLICT, again.type(), "INV-06");
        assertEquals(8, quantity(), "отказ количества не удержал");
    }

    @Tag("TC-417")
    @Test
    void confirmingAnApiReservationUsesItWithoutKeysAndLeavesTheQuantityAlone() {
        Reservation reserved = service.reserve(ORDER, API_PRODUCT, 3);
        int eventsBefore = stockEvents().size();
        Confirmation confirmation = service.confirm(ORDER, API_PRODUCT, 3);
        assertEquals(Reservation.USED, confirmation.reservation().status());
        assertFalse(confirmation.reReserved());
        assertEquals(reserved.id(), confirmation.reservation().id());
        assertEquals(0, stock.keys.held(reserved.id()));
        assertFalse(stock.keys.isIssued(reserved.id()), "ключа у товара по API нет, его пришлёт продавец");
        assertEquals(7, quantity());
        assertEquals(eventsBefore, stockEvents().size(), "количество не менялось, событие остатка не пишется");
        assertTrue(stock.queue.scheduled.isEmpty(), "таймер снят");

        Confirmation repeat = service.confirm(ORDER, API_PRODUCT, 3);
        assertEquals(reserved.id(), repeat.reservation().id(), "повтор возвращает тот же резерв");
        assertEquals(7, quantity());
    }

    @Tag("TC-418")
    @Test
    void anExpiredReservationGivesTheQuantityBackOnceEvenIfTheExpiryIsDeliveredTwice() {
        Reservation reservation = service.reserve(ORDER, API_PRODUCT, 3);
        stock.clock.advance(TTL);
        assertEquals(Expiry.EXPIRED, service.expire(reservation.id(), Detected.TIMER));
        assertEquals(10, quantity());
        int changes = stock.apiStock.changes;
        assertEquals(Expiry.GONE, service.expire(reservation.id(), Detected.RECONCILIATION));
        assertEquals(10, quantity(), "повторное истечение количество не прибавляет");
        assertEquals(changes, stock.apiStock.changes);
        assertEquals(List.of(7, 10), stockEvents().stream().map(e -> e.get("available")).toList());
        assertEquals(1, stock.events("reservation.expired").size());
    }

    @Tag("TC-418")
    @Test
    void cancellingBeforePaymentGivesTheQuantityBackOnceAndKeepsTheReason() {
        service.reserve(ORDER, API_PRODUCT, 4);
        Optional<Reservation> released = service.release(ORDER, Reservation.PAYMENT_DECLINED);
        assertTrue(released.isPresent());
        assertEquals(Reservation.PAYMENT_DECLINED, released.get().releaseReason());
        assertEquals(10, quantity());
        assertTrue(service.release(ORDER, Reservation.PAYMENT_DECLINED).isEmpty(), "повторное событие ничего не меняет");
        assertEquals(10, quantity());
        assertEquals(1, stock.events("reservation.released").size());
        assertEquals(List.of(6, 10), stockEvents().stream().map(e -> e.get("available")).toList());
    }

    @Tag("TC-418")
    @Test
    void cancellingAfterPaymentReleasesTheUsedReservationOfAnApiProductAndReturnsTheQuantityOnce() {
        Reservation reserved = service.reserve(ORDER, API_PRODUCT, 3);
        service.confirm(ORDER, API_PRODUCT, 3);
        Optional<Reservation> released = service.release(ORDER, Reservation.ORDER_CANCELLED);
        assertTrue(released.isPresent(), "SM-08/T5: использован > снят");
        assertEquals(reserved.id(), released.get().id());
        assertEquals(Reservation.RELEASED, stock.reservations.rows.get(reserved.id()).status());
        assertEquals(Reservation.ORDER_CANCELLED, stock.reservations.rows.get(reserved.id()).releaseReason());
        assertEquals(10, quantity());
        FakeStock.Tx.Event event = stock.events("reservation.released").get(0);
        assertEquals(Map.of("reservationId", reserved.id().toString(), "orderId", ORDER.toString(), "productId", API_PRODUCT.toString(),
                "quantity", 3, "reason", "order_cancelled"), event.data());
        assertEquals(10, stockEvents().get(stockEvents().size() - 1).get("available"));

        int changes = stock.apiStock.changes;
        assertTrue(service.release(ORDER, Reservation.ORDER_CANCELLED).isEmpty(), "повтор события: резерв уже снят");
        assertEquals(changes, stock.apiStock.changes);
        assertEquals(10, quantity());
    }

    @Tag("TC-418")
    @Test
    void aPaymentDeclinedEventNeverReleasesAUsedReservation() {
        Reservation reserved = service.reserve(ORDER, API_PRODUCT, 3);
        service.confirm(ORDER, API_PRODUCT, 3);
        assertTrue(service.release(ORDER, Reservation.PAYMENT_DECLINED).isEmpty(), "оплаченный заказ отказ платежа не снимает");
        assertEquals(Reservation.USED, stock.reservations.rows.get(reserved.id()).status());
        assertEquals(7, quantity());
        assertTrue(stock.events("reservation.released").isEmpty());
    }

    @Tag("TC-418")
    @Test
    void cancellingAnOrderThatWasNeverReservedChangesNothing() {
        assertTrue(service.release(UUID.randomUUID(), Reservation.ORDER_CANCELLED).isEmpty());
        assertEquals(10, quantity());
        assertTrue(stock.transaction.events.isEmpty());
    }

    @Tag("TC-418")
    @Test
    void issuedKeysOfAPoolProductNeverReturnToCirculationOnCancellation() {
        stock.keys.load(PRODUCT, 5);
        Reservation reserved = service.reserve(ORDER, PRODUCT, 2);
        service.confirm(ORDER, PRODUCT, 2);
        assertTrue(service.release(ORDER, Reservation.ORDER_CANCELLED).isEmpty(), "INV-04: выданный ключ не возвращается");
        assertEquals(Reservation.USED, stock.reservations.rows.get(reserved.id()).status());
        assertTrue(stock.keys.isIssued(reserved.id()));
        assertEquals(3, stock.keys.free(PRODUCT));
    }

    @Tag("TC-417")
    @Test
    void latePaymentAfterTheExpiryTakesTheQuantityAgainAndUsesTheNewReservation() {
        Reservation first = service.reserve(ORDER, API_PRODUCT, 3);
        stock.clock.advance(TTL);
        service.expire(first.id(), Detected.TIMER);
        assertEquals(10, quantity());

        Confirmation confirmation = service.confirm(ORDER, API_PRODUCT, 3);
        assertTrue(confirmation.reReserved());
        assertEquals(Reservation.USED, confirmation.reservation().status());
        assertEquals(7, quantity());
        assertEquals(0, stock.keys.held(confirmation.reservation().id()));
        assertEquals(10, stock.apiStock.rows.get(API_PRODUCT).quantity() + 3, "количество восстановлено и взято заново ровно один раз");
    }

    @Tag("TC-417")
    @Test
    void latePaymentWhenTheQuantityIsGoneIsRefusedAndNothingIsCreated() {
        Reservation first = service.reserve(ORDER, API_PRODUCT, 3);
        stock.clock.advance(TTL);
        service.expire(first.id(), Detected.TIMER);
        service.reserve(OTHER_ORDER, API_PRODUCT, 9);
        int reservations = stock.reservations.rows.size();
        ProblemException e = refused(() -> service.confirm(ORDER, API_PRODUCT, 3));
        assertEquals(ProblemType.INSUFFICIENT_STOCK, e.type());
        assertEquals(1, e.extensions().get("available"));
        assertEquals(1, quantity());
        assertEquals(reservations, stock.reservations.rows.size());
    }

    @Tag("TC-416")
    @Test
    void theApiProductStockVersionOfTheEventGrowsByOnePerQuantityChange() {
        Reservation first = service.reserve(ORDER, API_PRODUCT, 1);
        service.reserve(OTHER_ORDER, API_PRODUCT, 2);
        stock.clock.advance(TTL);
        service.expire(first.id(), Detected.TIMER);
        assertEquals(List.of(1L, 2L, 3L), stockEvents().stream().map(e -> e.get("version")).toList());
        assertEquals(List.of(9, 7, 8), stockEvents().stream().map(e -> e.get("available")).toList());
        assertEquals(3L, (long) stock.products.versions.get(API_PRODUCT));
        assertFalse(stock.products.versions.containsKey(PRODUCT), "пул другого товара не затронут");
    }
}
