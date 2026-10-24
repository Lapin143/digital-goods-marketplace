package dgm.platform.support.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.events.RetryableEventException;
import dgm.kit.time.ManualClock;
import dgm.platform.support.service.SupportEvents;
import dgm.platform.support.service.SupportService;
import dgm.platform.support.testing.InMemorySupport;
import dgm.platform.support.testing.RecordingOutbox;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Обработчики {@code support-event-handler}: что объявлено в контракте событий и как поля события доходят до ядра поддержки (Р-137). */
@Tag("TC-609")
class SupportHandlersTest {

    private static final UUID ORDER = UUID.fromString("0199c2a4-5e10-7a3b-9c1d-2f4e6a8b0c1d");
    private static final UUID BUYER = UUID.fromString("3f8e1d52-7b6a-4c90-8d21-5a9b0e7c4f13");
    private static final UUID DELIVERY = UUID.fromString("0199c2a5-9d01-7e12-a3b4-c5d6e7f80910");
    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");

    private InMemorySupport repository;
    private RecordingOutbox outbox;
    private OrderSupportHandler orders;
    private DeliverySupportHandler deliveries;

    @BeforeEach
    void setUp() {
        repository = new InMemorySupport();
        outbox = new RecordingOutbox();
        SupportService service = new SupportService(repository, outbox, new ManualClock(T0));
        orders = new OrderSupportHandler(service);
        deliveries = new DeliverySupportHandler(service);
    }

    private static EventEnvelope event(String type, String source, String aggregate, Instant time, Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), source, type, time, ORDER.toString(), 1, aggregate,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90222-01", UUID.randomUUID().toString(), data);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private EventEnvelope paid() {
        return event("order.paid", "order-service", "Order", T0, map("orderId", ORDER.toString(), "orderNumber", 1042,
                "buyerId", BUYER.toString(), "productTitle", "Лунная тропа"));
    }

    private EventEnvelope delivery(String type, Instant time, Object... pairs) {
        Map<String, Object> data = map("deliveryId", DELIVERY.toString(), "orderId", ORDER.toString());
        for (int i = 0; i < pairs.length; i += 2) {
            data.put((String) pairs[i], pairs[i + 1]);
        }
        return event(type, "delivery-service", "Delivery", time, data);
    }

    @Test
    void handlersDeclareTheContractOfTheCatalog() {
        assertEquals("support-event-handler", orders.name());
        assertEquals("support-event-handler", deliveries.name());
        assertEquals("order.events", orders.topic());
        assertEquals("delivery.events", deliveries.topic());
        assertEquals(Set.of("order.paid", "order.issued"), orders.types());
        assertEquals(Set.of("delivery.accepted", "delivery.delivered", "delivery.failed", "delivery.overdue"), deliveries.types());
        assertEquals("support", orders.module());
        assertEquals("support", deliveries.module());
        assertEquals(1, orders.maxSchemaVersion());
        assertEquals(1, deliveries.maxSchemaVersion());
    }

    @Test
    void paidEventCreatesTheReadModelFromTheOrderNumberAndTheBuyer() {
        assertEquals(HandleResult.done(), orders.handle(paid()));

        assertEquals(1042, repository.findOrderView(ORDER).orElseThrow().orderNumber());
        assertEquals(BUYER, repository.findOrderView(ORDER).orElseThrow().buyerId());
    }

    @Test
    void issuedEventRecordsTheFirstIssueTime() {
        orders.handle(paid());

        orders.handle(event("order.issued", "order-service", "Order", T0.plusSeconds(9), map("orderId", ORDER.toString(), "orderNumber", 1042,
                "buyerId", BUYER.toString(), "issuedAt", "2026-10-03T12:03:49.000Z")));

        assertEquals(Instant.parse("2026-10-03T12:03:49Z"), repository.findOrderView(ORDER).orElseThrow().issuedAt().orElseThrow());
    }

    @Test
    void failedDeliveryThroughTheHandlerOpensTheTicketAndDeliveredClosesIt() {
        orders.handle(paid());

        assertEquals(HandleResult.done(), deliveries.handle(delivery("delivery.failed", T0.plusSeconds(60), "reason", "attempts_exhausted",
                "attempts", 6)));
        assertEquals(1, outbox.events(SupportEvents.CREATED).size());

        deliveries.handle(delivery("delivery.delivered", T0.plusSeconds(120), "deliveredAt", "2026-10-03T12:02:00.000Z"));
        assertEquals("resolved", repository.tickets().get(0).status);
        assertEquals(1, outbox.events(SupportEvents.RESOLVED).size());
    }

    @Test
    void acceptedAndOverdueEventsReachTheCore() {
        orders.handle(paid());

        deliveries.handle(delivery("delivery.accepted", T0.plusSeconds(5), "deliveryType", "primary", "attemptNo", 1));
        assertEquals("sent", repository.findOrderView(ORDER).orElseThrow().deliveryStatus().orElseThrow());

        deliveries.handle(delivery("delivery.overdue", T0.plusSeconds(1800), "paidAt", "2026-10-03T12:00:00.000Z", "thresholdSeconds", 1800));
        assertEquals("delivery_overdue", repository.tickets().get(0).reason);
    }

    @Test
    void ticketEventBeforeThePaidEventIsRetriedNotLostAndStatusEventsAreSkipped() {
        assertThrows(RetryableEventException.class, () -> deliveries.handle(delivery("delivery.failed", T0, "reason", "attempts_exhausted")));
        assertThrows(RetryableEventException.class, () -> deliveries.handle(delivery("delivery.overdue", T0, "paidAt", "2026-10-03T12:00:00.000Z",
                "thresholdSeconds", 1800)));
        assertEquals(HandleResult.ignored("no-order-view"), deliveries.handle(delivery("delivery.accepted", T0, "deliveryType", "primary")));
        assertEquals(HandleResult.ignored("no-order-view"), deliveries.handle(delivery("delivery.delivered", T0)));
        assertTrue(repository.tickets().isEmpty());
    }

    @Test
    void brokenFieldsAreFatalBecauseARetryChangesNothing() {
        assertThrows(FatalEventException.class, () -> orders.handle(event("order.paid", "order-service", "Order", T0,
                map("orderId", "не-идентификатор", "orderNumber", 1042, "buyerId", BUYER.toString()))));
        assertThrows(FatalEventException.class, () -> orders.handle(event("order.paid", "order-service", "Order", T0,
                map("orderId", ORDER.toString(), "orderNumber", 0, "buyerId", BUYER.toString()))));
        assertThrows(FatalEventException.class, () -> orders.handle(event("order.issued", "order-service", "Order", T0,
                map("orderId", ORDER.toString(), "orderNumber", 1042, "buyerId", BUYER.toString(), "issuedAt", "вчера"))));
        assertThrows(FatalEventException.class, () -> deliveries.handle(delivery("delivery.failed", T0)), "нет поля reason");
        assertThrows(FatalEventException.class, () -> deliveries.handle(event("delivery.delivered", "delivery-service", "Delivery", T0,
                map("deliveryId", DELIVERY.toString()))), "нет поля orderId");
        assertThrows(FatalEventException.class, () -> deliveries.handle(delivery("delivery.refunded", T0)), "неизвестный тип");
        assertTrue(repository.tickets().isEmpty());
        assertEquals(0, repository.viewCount());
    }

    @Test
    void orderNumberOfAnotherOrderIsFatalAtOnceNotRetriedForThirtyOneSeconds() {
        orders.handle(paid());
        UUID other = UUID.fromString("0199c2a4-5e10-7a3b-9c1d-2f4e6a8b0c99");

        assertThrows(FatalEventException.class, () -> orders.handle(event("order.paid", "order-service", "Order", T0,
                map("orderId", other.toString(), "orderNumber", 1042, "buyerId", BUYER.toString()))));
        assertThrows(FatalEventException.class, () -> orders.handle(event("order.issued", "order-service", "Order", T0,
                map("orderId", other.toString(), "orderNumber", 1042, "buyerId", BUYER.toString(), "issuedAt", "2026-10-03T12:03:49.000Z"))));
        assertEquals(1, repository.viewCount(), "чужой номер вторую строку не создаёт");
    }
}
