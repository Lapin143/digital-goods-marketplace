package dgm.platform.notification.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.time.ManualClock;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.service.NotificationService;
import dgm.platform.notification.testing.FakeAdmins;
import dgm.platform.notification.testing.InMemoryNotifications;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Шесть писем среза 2 из событий (Р-138, Р-153): три покупателю и три администраторам. Проверяется контракт обработчиков, параметры
 * шаблонов, получатели, дедупликация по тройке «событие, шаблон, получатель» и то, что в очереди нет ни адресов, ни значений ключей.
 */
@Tag("TC-610")
class OrderLettersHandlersTest {

    private static final UUID ORDER = UUID.fromString("0199c2a4-5e10-7a3b-9c1d-2f4e6a8b0c1d");
    private static final UUID BUYER = UUID.fromString("3f8e1d52-7b6a-4c90-8d21-5a9b0e7c4f13");
    private static final UUID ADMIN_1 = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID ADMIN_2 = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    private InMemoryNotifications queue;
    private FakeAdmins admins;
    private OrderLetterHandler orders;
    private PaymentAlertHandler payments;
    private DeliveryAlertHandler deliveries;

    @BeforeEach
    void setUp() {
        queue = new InMemoryNotifications();
        admins = new FakeAdmins().with(ADMIN_1, ADMIN_2);
        NotificationService service = new NotificationService(queue, new Letters(ZoneId.of("Europe/Moscow"), "МСК"), new ManualClock(NOW));
        orders = new OrderLetterHandler(service);
        payments = new PaymentAlertHandler(admins, service);
        deliveries = new DeliveryAlertHandler(admins, service);
    }

    private static EventEnvelope event(String type, Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), "order-service", type, NOW, ORDER.toString(), 1, "Order",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90222-01", UUID.randomUUID().toString(), data);
    }

    private static Map<String, Object> money(long amount) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", "RUB");
        return money;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static EventEnvelope created() {
        return event("order.created", map("orderId", ORDER.toString(), "orderNumber", 1042, "buyerId", BUYER.toString(), "total", money(149900),
                "paymentSessionExpiresAt", "2026-10-03T12:12:02.000Z"));
    }

    private static EventEnvelope paid() {
        return event("order.paid", map("orderId", ORDER.toString(), "orderNumber", 1042, "buyerId", BUYER.toString(),
                "productTitle", "Лунная тропа: ключ Steam", "total", money(149900),
                "delivery", map("channel", "email", "address", "buyer@example.com")));
    }

    private static EventEnvelope refunded() {
        return event("order.refunded", map("orderId", ORDER.toString(), "orderNumber", 1042, "buyerId", BUYER.toString(), "total", money(149900),
                "refundReason", "late_payment"));
    }

    @Test
    void handlersDeclareTheContractOfTheCatalog() {
        assertEquals("notification-handler", orders.name());
        assertEquals("notification-handler", payments.name());
        assertEquals("notification-handler", deliveries.name());
        assertEquals("order.events", orders.topic());
        assertEquals("payment.events", payments.topic());
        assertEquals("delivery.events", deliveries.topic());
        assertEquals(Set.of("order.created", "order.paid", "order.refunded"), orders.types());
        assertEquals(Set.of("payment.refund-escalated"), payments.types());
        assertEquals(Set.of("delivery.failed", "delivery.overdue"), deliveries.types());
        for (EventHandler handler : List.<EventHandler>of(orders, payments, deliveries)) {
            assertEquals("notification", handler.module());
            assertEquals(1, handler.maxSchemaVersion());
        }
    }

    @Test
    void orderCreatedQueuesTheLetterForTheBuyerWithTheNumberTheSumTheLinkAndTheDeadline() {
        EventEnvelope event = created();

        assertEquals(HandleResult.done(), orders.handle(event));

        assertEquals(1, queue.all().size());
        InMemoryNotifications.Row row = queue.all().get(0);
        assertEquals(BUYER, row.source.userId());
        assertEquals("order-created", row.source.template());
        assertEquals(event.id(), row.source.sourceEventId());
        assertEquals(Map.of("orderNumber", "1042", "total", "149900 RUB", "orderId", ORDER.toString(), "payUntil", "2026-10-03T12:12:02.000Z"),
                row.source.params());
    }

    @Test
    void orderPaidQueuesTheLetterWithTheProductAndNoAddress() {
        orders.handle(paid());

        InMemoryNotifications.Row row = queue.all().get(0);
        assertEquals(BUYER, row.source.userId());
        assertEquals("order-paid", row.source.template());
        assertEquals(Map.of("orderNumber", "1042", "productTitle", "Лунная тропа: ключ Steam", "total", "149900 RUB"), row.source.params());
        assertFalse(row.source.params().toString().contains("buyer@example.com"), "адрес снимка в очередь не попадает (INV-44)");
    }

    @Test
    void orderRefundedQueuesTheLetterWithTheReason() {
        orders.handle(refunded());

        InMemoryNotifications.Row row = queue.all().get(0);
        assertEquals("order-refunded", row.source.template());
        assertEquals(Map.of("orderNumber", "1042", "total", "149900 RUB", "refundReason", "late_payment"), row.source.params());
    }

    @Test
    void theSameEventGivesOneLetterOnRedelivery() {
        EventEnvelope event = paid();

        assertEquals(HandleResult.done(), orders.handle(event));
        assertEquals(HandleResult.ignored("duplicate-notification"), orders.handle(event));

        assertEquals(1, queue.all().size());
    }

    @Test
    void refundEscalationGoesToEveryActiveAdmin() {
        EventEnvelope event = event("payment.refund-escalated", map("paymentId", UUID.randomUUID().toString(), "orderId", ORDER.toString(),
                "amount", money(149900), "attempts", 5));

        assertEquals(HandleResult.done(), payments.handle(event));

        assertEquals(2, queue.all().size());
        assertEquals(Set.of(ADMIN_1, ADMIN_2), Set.of(queue.all().get(0).source.userId(), queue.all().get(1).source.userId()));
        for (InMemoryNotifications.Row row : queue.all()) {
            assertEquals("payment-refund-escalated", row.source.template());
            assertEquals(Map.of("orderId", ORDER.toString(), "amount", "149900 RUB", "attempts", "5"), row.source.params());
            assertEquals(event.id(), row.source.sourceEventId());
        }
    }

    @Test
    void deliveryFailureAndOverdueGoToEveryActiveAdmin() {
        deliveries.handle(event("delivery.failed", map("deliveryId", UUID.randomUUID().toString(), "orderId", ORDER.toString(),
                "reason", "attempts_exhausted", "attempts", 6)));
        deliveries.handle(event("delivery.overdue", map("deliveryId", UUID.randomUUID().toString(), "orderId", ORDER.toString(),
                "paidAt", "2026-10-03T12:03:41.000Z", "thresholdSeconds", 1800)));

        assertEquals(4, queue.all().size());
        InMemoryNotifications.Row failed = queue.all().get(0);
        assertEquals("delivery-failed", failed.source.template());
        assertEquals(Map.of("orderId", ORDER.toString(), "reason", "attempts_exhausted", "attempts", "6"), failed.source.params());
        InMemoryNotifications.Row overdue = queue.all().get(2);
        assertEquals("delivery-overdue", overdue.source.template());
        assertEquals(Map.of("orderId", ORDER.toString(), "paidAt", "2026-10-03T12:03:41.000Z", "threshold", "1800"), overdue.source.params());
    }

    @Test
    void redeliveryOfAnAdminAlertCreatesNothingNewAndAdminAddedLaterGetsOnlyHisOwnLetter() {
        EventEnvelope event = event("delivery.failed", map("deliveryId", UUID.randomUUID().toString(), "orderId", ORDER.toString(),
                "reason", "attempts_exhausted", "attempts", 6));
        deliveries.handle(event);

        assertEquals(HandleResult.ignored("duplicate-notification"), deliveries.handle(event));
        UUID third = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a3");
        admins.with(third);
        assertEquals(HandleResult.done(), deliveries.handle(event), "новому администратору письмо создаётся, старым нет");

        assertEquals(3, queue.all().size());
    }

    @Test
    void withoutAdminsTheAlertIsSkippedWithAReasonNotLostSilently() {
        PaymentAlertHandler lonely = new PaymentAlertHandler(new FakeAdmins(),
                new NotificationService(queue, new Letters(ZoneId.of("Europe/Moscow"), "МСК"), new ManualClock(NOW)));

        HandleResult result = lonely.handle(event("payment.refund-escalated", map("paymentId", UUID.randomUUID().toString(),
                "orderId", ORDER.toString(), "amount", money(100), "attempts", 5)));

        assertEquals(HandleResult.ignored("no-admins"), result);
        assertTrue(queue.all().isEmpty());
    }

    @Test
    void brokenOrUnknownFieldsAreFatalBecauseARetryChangesNothing() {
        assertThrows(FatalEventException.class, () -> orders.handle(event("order.created", map("orderId", ORDER.toString(), "orderNumber", 1042,
                "buyerId", "не-идентификатор", "total", money(1), "paymentSessionExpiresAt", "2026-10-03T12:12:02.000Z"))));
        assertThrows(FatalEventException.class, () -> orders.handle(event("order.paid", map("orderId", ORDER.toString(), "orderNumber", -5,
                "buyerId", BUYER.toString(), "productTitle", "x", "total", money(1)))));
        assertThrows(FatalEventException.class, () -> orders.handle(event("order.paid", map("orderId", ORDER.toString(), "orderNumber", 1,
                "buyerId", BUYER.toString(), "productTitle", "x", "total", "149900 RUB"))));
        assertThrows(FatalEventException.class, () -> orders.handle(event("order.refunded", map("orderId", ORDER.toString(), "orderNumber", 1,
                "buyerId", BUYER.toString(), "total", money(1), "refundReason", "по настроению"))), "причины нет в списке шаблона");
        assertThrows(FatalEventException.class, () -> deliveries.handle(event("delivery.failed", map("orderId", ORDER.toString(),
                "reason", "неизвестная", "attempts", 1))));
        assertThrows(FatalEventException.class, () -> deliveries.handle(event("delivery.accepted", map("orderId", ORDER.toString()))));
        assertTrue(queue.all().isEmpty());
    }

    @Test
    void noLetterParameterHoldsAnAddressOrAKeyValue() {
        orders.handle(created());
        orders.handle(paid());
        orders.handle(refunded());
        payments.handle(event("payment.refund-escalated", map("paymentId", UUID.randomUUID().toString(), "orderId", ORDER.toString(),
                "amount", money(1), "attempts", 5)));

        for (InMemoryNotifications.Row row : queue.all()) {
            String params = row.source.params().toString();
            assertFalse(params.contains("@"), params);
            assertFalse(row.source.params().containsKey("email"), params);
            assertFalse(row.source.params().containsKey("address"), params);
        }
    }
}
