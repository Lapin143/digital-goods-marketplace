package dgm.platform.notification.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.json.Json;
import dgm.kit.time.ManualClock;
import dgm.platform.notification.testing.InMemoryWebhookDeliveries;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Постановка webhook продавцу в очередь (Р-193, компонент {@code seller-webhook-handler}): четыре события заказа, тело из полей события, одно событие
 * даёт одно уведомление, нарушение схемы уходит в DLQ. Главное для TC-974: ни в теле, ни в строке нет данных покупателя (INV-44), даже когда само
 * событие их несёт ({@code order.paid} с адресом доставки).
 */
@Tag("TC-974")
class SellerWebhookHandlerTest {

    private static final UUID ORDER = UUID.fromString("0199c2a4-5e10-7a3b-9c1d-2f4e6a8b0c1d");
    private static final UUID BUYER = UUID.fromString("3f8e1d52-7b6a-4c90-8d21-5a9b0e7c4f13");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final String BUYER_EMAIL = "buyer-canary-4471@example.test";
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Instant EVENT_TIME = Instant.parse("2026-11-09T09:59:59.500Z");

    private InMemoryWebhookDeliveries queue;
    private SellerWebhookHandler handler;

    @BeforeEach
    void setUp() {
        queue = new InMemoryWebhookDeliveries();
        handler = new SellerWebhookHandler(queue, new ManualClock(NOW));
    }

    private static Map<String, Object> money() {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", 299_800L);
        money.put("currency", "RUB");
        return money;
    }

    private static Map<String, Object> paid() {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("channel", "email");
        delivery.put("address", BUYER_EMAIL);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", ORDER.toString());
        data.put("orderNumber", 1042);
        data.put("buyerId", BUYER.toString());
        data.put("sellerId", SELLER.toString());
        data.put("productId", PRODUCT.toString());
        data.put("productTitle", "Ключ для " + BUYER_EMAIL);
        data.put("quantity", 2);
        data.put("total", money());
        data.put("commission", money());
        data.put("paidAt", "2026-11-09T09:59:59.000Z");
        data.put("latePayment", false);
        data.put("issuanceMethod", "seller_api");
        data.put("delivery", delivery);
        return data;
    }

    private static Map<String, Object> issued() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", ORDER.toString());
        data.put("orderNumber", 1042);
        data.put("buyerId", BUYER.toString());
        data.put("sellerId", SELLER.toString());
        data.put("productId", PRODUCT.toString());
        data.put("total", money());
        data.put("commission", money());
        data.put("deliveryId", UUID.randomUUID().toString());
        data.put("issuedAt", "2026-11-09T09:59:59.900Z");
        data.put("quantity", 2);
        return data;
    }

    private static Map<String, Object> cancelled() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", ORDER.toString());
        data.put("buyerId", BUYER.toString());
        data.put("productId", PRODUCT.toString());
        data.put("quantity", 2);
        data.put("cancelReason", "seller_api_timeout");
        data.put("sellerId", SELLER.toString());
        data.put("orderNumber", 1042);
        data.put("total", money());
        return data;
    }

    private static Map<String, Object> refunded() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", ORDER.toString());
        data.put("orderNumber", 1042);
        data.put("buyerId", BUYER.toString());
        data.put("sellerId", SELLER.toString());
        data.put("total", money());
        data.put("refundReason", "seller_api_timeout");
        data.put("productId", PRODUCT.toString());
        data.put("quantity", 2);
        return data;
    }

    private static EventEnvelope event(UUID id, String type, Map<String, Object> data) {
        return new EventEnvelope(id, "order-service", type, EVENT_TIME, ORDER.toString(), 1, "Order",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90222-01", UUID.randomUUID().toString(), data);
    }

    @Test
    void theHandlerIsNamedAndSubscribedAsTheContractSays() {
        assertEquals("seller-webhook-handler", handler.name());
        assertEquals("order.events", handler.topic());
        assertEquals("notification", handler.module());
        assertEquals(Set.of("order.paid", "order.issued", "order.cancelled", "order.refunded"), handler.types());
        assertEquals(1, handler.maxSchemaVersion());
        assertTrue(EventHandler.class.isAssignableFrom(SellerWebhookHandler.class));
    }

    @Test
    void eachOfTheFourEventsGivesOneQueuedNotificationForTheSellerOfTheOrder() {
        Map<String, Map<String, Object>> events = new LinkedHashMap<>();
        events.put("order.paid", paid());
        events.put("order.issued", issued());
        events.put("order.cancelled", cancelled());
        events.put("order.refunded", refunded());
        for (Map.Entry<String, Map<String, Object>> e : events.entrySet()) {
            assertTrue(handler.handle(event(UUID.randomUUID(), e.getKey(), e.getValue())).applied(), e.getKey());
        }

        assertEquals(4, queue.rows().size());
        for (InMemoryWebhookDeliveries.Row row : queue.rows()) {
            assertEquals(SELLER, row.sellerId);
            assertEquals("queued", row.status);
            assertEquals(0, row.attempts);
            assertEquals(NOW, row.nextAttemptAt, "первая попытка сразу (пауза 0)");
            assertEquals(1042L, row.orderNumber);
        }
        assertEquals(List.of("order.paid", "order.issued", "order.cancelled", "order.refunded"),
                queue.rows().stream().map(r -> r.eventType).toList());
    }

    @Test
    void theBodyHoldsExactlyTheFieldsOfTheContractTakenFromTheEvent() {
        UUID eventId = UUID.fromString("0199e0a0-0000-7000-8000-000000000b01");
        handler.handle(event(eventId, "order.paid", paid()));

        Map<String, Object> body = queue.only().payload;
        assertEquals(Set.of("eventId", "type", "occurredAt", "orderNumber", "productId", "quantity", "amount"), body.keySet());
        assertEquals(eventId.toString(), body.get("eventId"));
        assertEquals(queue.only().eventId, eventId, "идентификатор события один и тот же в строке и в теле: по нему продавец отсекает дубли");
        assertEquals("order.paid", body.get("type"));
        assertEquals("2026-11-09T09:59:59.500Z", body.get("occurredAt"));
        assertEquals(1042L, body.get("orderNumber"));
        assertEquals(PRODUCT.toString(), body.get("productId"));
        assertEquals(2L, body.get("quantity"));
        assertEquals(money(), body.get("amount"));
    }

    @Test
    void thereIsNoDataOfTheBuyerInTheBodyEvenWhenTheEventCarriesIt() {
        for (Map.Entry<String, Map<String, Object>> e : Map.of("order.paid", paid(), "order.issued", issued(), "order.cancelled", cancelled(),
                "order.refunded", refunded()).entrySet()) {
            handler.handle(event(UUID.randomUUID(), e.getKey(), e.getValue()));
        }

        for (InMemoryWebhookDeliveries.Row row : queue.rows()) {
            String text = Json.write(row.payload);
            assertFalse(text.contains(BUYER_EMAIL), "адрес покупателя в теле " + row.eventType);
            assertFalse(text.contains(BUYER.toString()), "идентификатор покупателя в теле " + row.eventType);
            assertFalse(text.contains(ORDER.toString()), "внутренний идентификатор заказа продавцу не отдаётся, только короткий номер");
            assertFalse(text.contains("@") || text.contains("delivery") || text.contains("buyer") || text.contains("productTitle"), row.eventType);
            assertFalse(text.contains("seller_api_timeout"), "причина отмены в тело не входит");
        }
    }

    @Test
    void theSameEventTwiceGivesOneNotification() {
        EventEnvelope event = event(UUID.randomUUID(), "order.issued", issued());

        assertTrue(handler.handle(event).applied());
        HandleResult again = handler.handle(event);

        assertFalse(again.applied(), "повторная обработка события новой строки не даёт (ADR-006)");
        assertEquals("duplicate-webhook", again.reason());
        assertEquals(1, queue.rows().size());
    }

    @Test
    void anEventIssuedBeforeR2WithoutAnyOfTheAddedFieldsIsSkippedQuietlyAndNotDeadLettered() {
        Map<String, Object> cancelled = cancelled();
        cancelled.remove("sellerId");
        cancelled.remove("orderNumber");
        cancelled.remove("total");
        Map<String, Object> issued = issued();
        issued.remove("quantity");
        Map<String, Object> refunded = refunded();
        refunded.remove("productId");
        refunded.remove("quantity");

        for (Map.Entry<String, Map<String, Object>> e : Map.of("order.cancelled", cancelled, "order.issued", issued, "order.refunded", refunded).entrySet()) {
            HandleResult result = handler.handle(event(UUID.randomUUID(), e.getKey(), e.getValue()));

            assertFalse(result.applied(), e.getKey());
            assertEquals("legacy-event", result.reason(), e.getKey());
        }
        assertTrue(queue.rows().isEmpty(), "по событию до R2 уведомления нет: продавцу webhook тогда не обещали");
    }

    @Test
    void aPartOfTheAddedFieldsIsAMistakeOfThePublisherAndNotALegacyEvent() {
        Map<String, Object> onlyTotalMissing = cancelled();
        onlyTotalMissing.remove("total");
        assertThrows(FatalEventException.class, () -> handler.handle(event(UUID.randomUUID(), "order.cancelled", onlyTotalMissing)));

        Map<String, Object> onlyProductMissing = refunded();
        onlyProductMissing.remove("productId");
        assertThrows(FatalEventException.class, () -> handler.handle(event(UUID.randomUUID(), "order.refunded", onlyProductMissing)));
        assertTrue(queue.rows().isEmpty());
    }

    @Test
    void anEventWithoutTheSellerOrWithBrokenFieldsGoesToTheDeadLetterTopicAtOnce() {
        Map<String, Object> noSeller = cancelled();
        noSeller.remove("sellerId");
        assertThrows(FatalEventException.class, () -> handler.handle(event(UUID.randomUUID(), "order.cancelled", noSeller)));

        Map<String, Object> badNumber = issued();
        badNumber.put("orderNumber", 7);
        assertThrows(FatalEventException.class, () -> handler.handle(event(UUID.randomUUID(), "order.issued", badNumber)));

        Map<String, Object> noQuantity = refunded();
        noQuantity.remove("quantity");
        assertThrows(FatalEventException.class, () -> handler.handle(event(UUID.randomUUID(), "order.refunded", noQuantity)));

        Map<String, Object> badMoney = paid();
        badMoney.put("total", "299800 RUB");
        assertThrows(FatalEventException.class, () -> handler.handle(event(UUID.randomUUID(), "order.paid", badMoney)));

        assertThrows(FatalEventException.class, () -> handler.handle(event(UUID.randomUUID(), "order.created", paid())));
        assertTrue(queue.rows().isEmpty(), "ни одного уведомления из негодных событий");
    }
}
