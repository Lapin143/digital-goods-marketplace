package dgm.inventory.inventory.reservation;

import static dgm.inventory.inventory.reservation.FakeStock.PRODUCT;
import static dgm.inventory.inventory.reservation.FakeStock.SELLER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.inventory.inventory.reservation.domain.ReservationSettings;
import dgm.inventory.inventory.reservation.handler.ReservationReleaseHandler;
import dgm.inventory.inventory.reservation.service.ReservationService.Detected;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Обработчик {@code order.cancelled} (SM-08/T4 и T6, INV-06): причина снятия берётся из причины отмены, повтор события и отмена после
 * истечения срока безвредны, использованный резерв не трогается, некорректное событие считается фатальным.
 */
class ReservationReleaseHandlerTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final Duration TTL = Duration.ofMinutes(15);

    private FakeStock stock;
    private ReservationReleaseHandler handler;

    @BeforeEach
    void setUp() {
        stock = new FakeStock(new ReservationSettings(TTL, Duration.ofSeconds(30)));
        stock.keys.load(PRODUCT, 5);
        handler = new ReservationReleaseHandler(stock.service);
    }

    private EventEnvelope cancelled(Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), "order-service", "order.cancelled", stock.clock.instant(), String.valueOf(data.get("orderId")), 1, "Order",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", UUID.randomUUID().toString(), data);
    }

    private EventEnvelope cancelled(UUID orderId, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId.toString());
        data.put("cancelReason", reason);
        data.put("sellerId", SELLER.toString());
        return cancelled(data);
    }

    @Test
    void theHandlerIsDeclaredLikeTheEventContractSaysAndWorksUnderTheInventoryModule() {
        assertEquals("reservation-service", handler.name());
        assertEquals("inventory", handler.module());
        assertEquals("order.events", handler.topic());
        assertEquals(Set.of("order.cancelled"), handler.types());
        assertEquals(1, handler.maxSchemaVersion());
    }

    @Test
    void aDeclinedPaymentReleasesTheReservationWithThatReason() {
        Reservation reservation = stock.service.reserve(ORDER, PRODUCT, 3);
        HandleResult result = handler.handle(cancelled(ORDER, "payment_declined"));
        assertTrue(result.applied());
        assertEquals(Reservation.PAYMENT_DECLINED, stock.reservations.rows.get(reservation.id()).releaseReason());
        assertEquals(5, stock.keys.free(PRODUCT));
    }

    @Test
    void anyOtherCancellationReasonIsRecordedAsOrderCancelled() {
        Reservation reservation = stock.service.reserve(ORDER, PRODUCT, 2);
        assertTrue(handler.handle(cancelled(ORDER, "buyer_request")).applied());
        assertEquals(Reservation.ORDER_CANCELLED, stock.reservations.rows.get(reservation.id()).releaseReason());
    }

    @Test
    void aMissingReasonIsTreatedAsOrderCancelled() {
        Reservation reservation = stock.service.reserve(ORDER, PRODUCT, 2);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", ORDER.toString());
        assertTrue(handler.handle(cancelled(data)).applied());
        assertEquals(Reservation.ORDER_CANCELLED, stock.reservations.rows.get(reservation.id()).releaseReason());
    }

    @Test
    void aRepeatedEventIsIgnoredAndWritesNothing() {
        stock.service.reserve(ORDER, PRODUCT, 3);
        assertTrue(handler.handle(cancelled(ORDER, "order_cancelled")).applied());
        int events = stock.transaction.events.size();
        HandleResult again = handler.handle(cancelled(ORDER, "order_cancelled"));
        assertFalse(again.applied());
        assertEquals("no-active-reservation", again.reason());
        assertEquals(events, stock.transaction.events.size(), "повтор сигнала не меняет ни ключи, ни события (правило 11)");
    }

    @Test
    void anOrderWithoutAReservationIsIgnored() {
        HandleResult result = handler.handle(cancelled(UUID.randomUUID(), "order_cancelled"));
        assertFalse(result.applied());
        assertEquals("no-active-reservation", result.reason());
    }

    @Test
    void cancellationAfterTheTimerHasAlreadyExpiredTheReservationChangesNothing() {
        Reservation reservation = stock.service.reserve(ORDER, PRODUCT, 3);
        stock.clock.advance(TTL);
        stock.service.expire(reservation.id(), Detected.TIMER);
        assertFalse(handler.handle(cancelled(ORDER, "payment_declined")).applied());
        assertEquals(Reservation.EXPIRED, stock.reservations.rows.get(reservation.id()).releaseReason(), "причина первого снятия остаётся");
    }

    @Test
    void anUsedReservationKeepsItsKeys() {
        Reservation reservation = stock.service.reserve(ORDER, PRODUCT, 3);
        stock.reservations.markUsed(reservation.id());
        assertFalse(handler.handle(cancelled(ORDER, "order_cancelled")).applied());
        assertEquals(Reservation.USED, stock.reservations.rows.get(reservation.id()).status());
        assertEquals(3, stock.keys.held(reservation.id()));
    }

    @Test
    void anEventWithoutAValidOrderIdIsFatalBecauseRetryingCannotHelp() {
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("cancelReason", "order_cancelled");
        invalid.put("orderId", "не-идентификатор");
        assertThrows(FatalEventException.class, () -> handler.handle(cancelled(invalid)));
        Map<String, Object> absent = new LinkedHashMap<>();
        absent.put("cancelReason", "order_cancelled");
        assertThrows(FatalEventException.class, () -> handler.handle(cancelled(absent)));
    }
}
