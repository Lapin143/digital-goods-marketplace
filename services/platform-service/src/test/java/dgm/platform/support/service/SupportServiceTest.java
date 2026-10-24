package dgm.platform.support.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.HandleResult;
import dgm.kit.events.RetryableEventException;
import dgm.kit.time.ManualClock;
import dgm.platform.support.domain.OrderView;
import dgm.platform.support.testing.InMemorySupport;
import dgm.platform.support.testing.RecordingOutbox;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Ядро поддержки среза 2 (Р-137, SM-07): модель чтения заказа, обращение системы по {@code delivery.failed} и {@code delivery.overdue},
 * закрытие доставкой, обращение раньше {@code order.paid} (F3-21).
 */
@Tag("TC-609")
class SupportServiceTest {

    private static final UUID ORDER = UUID.fromString("0199c2a4-5e10-7a3b-9c1d-2f4e6a8b0c1d");
    private static final UUID BUYER = UUID.fromString("3f8e1d52-7b6a-4c90-8d21-5a9b0e7c4f13");
    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");

    private InMemorySupport repository;
    private RecordingOutbox outbox;
    private SupportService service;

    @BeforeEach
    void setUp() {
        repository = new InMemorySupport();
        outbox = new RecordingOutbox();
        service = new SupportService(repository, outbox, new ManualClock(T0));
    }

    private void paid() {
        service.onOrderPaid(ORDER, 1042, BUYER);
    }

    @Test
    void paidOrderCreatesTheReadModelWithTheBuyerAndTheNumber() {
        assertEquals(HandleResult.done(), service.onOrderPaid(ORDER, 1042, BUYER));

        OrderView view = repository.findOrderView(ORDER).orElseThrow();
        assertEquals(1042, view.orderNumber());
        assertEquals(BUYER, view.buyerId());
        assertEquals("paid", view.orderStatus());
        assertTrue(view.issuedAt().isEmpty());
        assertTrue(view.deliveryStatus().isEmpty());
    }

    @Test
    void issuedOrderSetsTheStatusAndKeepsTheFirstIssueTimeForever() {
        paid();
        service.onOrderIssued(ORDER, 1042, BUYER, T0.plusSeconds(10));
        service.onOrderIssued(ORDER, 1042, BUYER, T0.plusSeconds(999));

        OrderView view = repository.findOrderView(ORDER).orElseThrow();
        assertEquals("issued", view.orderStatus());
        assertEquals(T0.plusSeconds(10), view.issuedAt().orElseThrow());
    }

    @Test
    void issuedEventCreatesTheRowWhenItOvertakesThePaidEvent() {
        service.onOrderIssued(ORDER, 1042, BUYER, T0);
        service.onOrderPaid(ORDER, 1042, BUYER);

        OrderView view = repository.findOrderView(ORDER).orElseThrow();
        assertEquals("issued", view.orderStatus(), "позднее событие paid статус не откатывает");
        assertEquals(1, repository.viewCount());
    }

    @Test
    void acceptedDeliveryMakesTheStatusSentAndAnOlderEventDoesNot() {
        paid();

        assertEquals(HandleResult.done(), service.onDeliveryAccepted(ORDER, T0.plusSeconds(20)));
        assertEquals(HandleResult.ignored("stale-event"), service.onDeliveryAccepted(ORDER, T0.plusSeconds(5)));

        OrderView view = repository.findOrderView(ORDER).orElseThrow();
        assertEquals("sent", view.deliveryStatus().orElseThrow());
        assertEquals(T0.plusSeconds(20), view.deliveryUpdatedAt().orElseThrow());
    }

    @Test
    void failedDeliveryOpensOneSystemTicketAndPublishesTicketCreated() {
        paid();

        assertEquals(HandleResult.done(), service.onDeliveryFailed(ORDER, "attempts_exhausted", T0.plusSeconds(60)));

        InMemorySupport.Ticket ticket = repository.ticketsOf(ORDER).get(0);
        assertEquals("system", ticket.source);
        assertEquals("attempts_exhausted", ticket.reason);
        assertEquals(BUYER, ticket.buyerId);
        assertEquals("created", ticket.status);
        assertEquals("failed", repository.findOrderView(ORDER).orElseThrow().deliveryStatus().orElseThrow());
        RecordingOutbox.Event event = outbox.events(SupportEvents.CREATED).get(0);
        assertEquals("support.events", event.topic());
        assertEquals("Ticket", event.aggregateType());
        assertEquals(ticket.id.toString(), event.aggregateId());
        assertEquals(Map.of("ticketId", ticket.id.toString(), "orderId", ORDER.toString(), "buyerId", BUYER.toString(), "source", "system",
                "reason", "attempts_exhausted"), event.data());
    }

    @Test
    void rejectedAddressIsTheReasonNotDelivered() {
        paid();

        service.onDeliveryFailed(ORDER, "address_rejected", T0);

        assertEquals("not_delivered", repository.ticketsOf(ORDER).get(0).reason);
    }

    @Test
    void overdueDeliveryOpensATicketWithTheOverdueReason() {
        paid();

        assertEquals(HandleResult.done(), service.onDeliveryOverdue(ORDER, T0.plusSeconds(1800)));

        assertEquals("delivery_overdue", repository.ticketsOf(ORDER).get(0).reason);
        assertEquals(1, outbox.events(SupportEvents.CREATED).size());
        assertTrue(repository.findOrderView(ORDER).orElseThrow().deliveryStatus().isEmpty(), "контроль статус выдачи не меняет");
    }

    @Test
    void repeatedFailureAndOverdueAddNoSecondOpenTicket() {
        paid();

        service.onDeliveryOverdue(ORDER, T0.plusSeconds(1800));
        assertEquals(HandleResult.ignored("ticket-already-open"), service.onDeliveryFailed(ORDER, "attempts_exhausted", T0.plusSeconds(1900)));
        assertEquals(HandleResult.ignored("ticket-already-open"), service.onDeliveryFailed(ORDER, "attempts_exhausted", T0.plusSeconds(1901)));

        assertEquals(1, repository.ticketsOf(ORDER).size());
        assertEquals(1, outbox.events(SupportEvents.CREATED).size());
    }

    @Test
    void deliveryClosesTheOpenTicketBySystemAndPublishesTicketResolved() {
        paid();
        service.onDeliveryFailed(ORDER, "attempts_exhausted", T0.plusSeconds(60));

        assertEquals(HandleResult.done(), service.onDeliveryDelivered(ORDER, T0.plusSeconds(120)));

        InMemorySupport.Ticket ticket = repository.ticketsOf(ORDER).get(0);
        assertEquals("resolved", ticket.status);
        assertEquals("system", ticket.resolvedBy);
        assertEquals("delivered", repository.findOrderView(ORDER).orElseThrow().deliveryStatus().orElseThrow());
        RecordingOutbox.Event event = outbox.events(SupportEvents.RESOLVED).get(0);
        assertEquals(Map.of("ticketId", ticket.id.toString(), "orderId", ORDER.toString(), "buyerId", BUYER.toString(), "resolvedBy", "system"),
                event.data());
    }

    @Test
    void deliveryWithoutATicketOnlyUpdatesTheStatus() {
        paid();

        assertEquals(HandleResult.done(), service.onDeliveryDelivered(ORDER, T0.plusSeconds(30)));

        assertTrue(outbox.events().isEmpty());
        assertEquals("delivered", repository.findOrderView(ORDER).orElseThrow().deliveryStatus().orElseThrow());
    }

    @Test
    void aTicketIsNotReopenedButAnewFailureOpensANewOne() {
        paid();
        service.onDeliveryFailed(ORDER, "attempts_exhausted", T0.plusSeconds(60));
        service.onDeliveryDelivered(ORDER, T0.plusSeconds(120));

        assertEquals(HandleResult.done(), service.onDeliveryFailed(ORDER, "attempts_exhausted", T0.plusSeconds(500)));

        assertEquals(2, repository.ticketsOf(ORDER).size());
        assertEquals(Set.of("resolved", "created"), Set.of(repository.tickets().get(0).status, repository.tickets().get(1).status));
    }

    @Test
    void failureOlderThanTheConfirmedDeliveryOpensNoTicket() {
        paid();
        service.onDeliveryDelivered(ORDER, T0.plusSeconds(300));

        assertEquals(HandleResult.ignored("stale-event"), service.onDeliveryFailed(ORDER, "attempts_exhausted", T0.plusSeconds(200)));
        assertEquals(HandleResult.ignored("stale-event"), service.onDeliveryOverdue(ORDER, T0.plusSeconds(250)));

        assertTrue(repository.tickets().isEmpty());
        assertTrue(outbox.events().isEmpty());
    }

    @Test
    void deliveryOlderThanAnAppliedEventClosesNothing() {
        paid();
        service.onDeliveryFailed(ORDER, "attempts_exhausted", T0.plusSeconds(400));

        assertEquals(HandleResult.ignored("stale-event"), service.onDeliveryDelivered(ORDER, T0.plusSeconds(100)));

        assertEquals("created", repository.ticketsOf(ORDER).get(0).status);
        assertTrue(outbox.events(SupportEvents.RESOLVED).isEmpty());
    }

    @Test
    void ticketEventsBeforeTheReadModelAreRetriedAndCreateNothing() {
        assertThrows(RetryableEventException.class, () -> service.onDeliveryFailed(ORDER, "attempts_exhausted", T0));
        assertThrows(RetryableEventException.class, () -> service.onDeliveryOverdue(ORDER, T0));

        assertTrue(repository.tickets().isEmpty(), "обращение без покупателя не создаётся (F3-21)");
        assertTrue(outbox.events().isEmpty());
        assertEquals(0, repository.viewCount());
    }

    @Test
    void statusEventsBeforeTheReadModelAreSkippedNotRetriedBecauseNothingCanBeOpenYet() {
        assertEquals(HandleResult.ignored("no-order-view"), service.onDeliveryAccepted(ORDER, T0));
        assertEquals(HandleResult.ignored("no-order-view"), service.onDeliveryDelivered(ORDER, T0));

        assertTrue(repository.tickets().isEmpty());
        assertTrue(outbox.events().isEmpty());
        assertEquals(0, repository.viewCount(), "статус без заказа строку модели не создаёт");
    }

    @Test
    void retryAfterThePaidEventCreatesTheTicketWithTheBuyer() {
        assertThrows(RetryableEventException.class, () -> service.onDeliveryFailed(ORDER, "attempts_exhausted", T0));
        paid();

        assertEquals(HandleResult.done(), service.onDeliveryFailed(ORDER, "attempts_exhausted", T0));

        assertEquals(BUYER, repository.ticketsOf(ORDER).get(0).buyerId);
    }

    @Test
    void ticketEventsCarryNoKeysAddressesOrCodes() {
        paid();
        service.onDeliveryFailed(ORDER, "attempts_exhausted", T0);
        service.onDeliveryDelivered(ORDER, T0.plusSeconds(10));

        for (RecordingOutbox.Event event : outbox.events()) {
            String text = event.data().toString();
            assertFalse(text.contains("@"), text);
            assertFalse(text.toLowerCase().contains("key"), text);
            assertFalse(text.toLowerCase().contains("code"), text);
        }
    }
}
