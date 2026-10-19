package dgm.delivery.delivery.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.delivery.delivery.service.DeliveryIntake;
import dgm.delivery.delivery.service.DeliverySettings;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.time.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Потребитель {@code order.paid} (TC-602, компонент {@code delivery-domain}): создаёт выдачу и контроль, повтор отмечает как «уже есть»,
 * событие не по схеме это неисправимая ошибка, а в её тексте нет ни адреса, ни названия товара.
 */
@Tag("TC-602")
class OrderPaidHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");

    private FakeDeliveries deliveries;
    private OrderPaidHandler handler;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        DeliveryIntake intake = new DeliveryIntake(deliveries, new DeliverySettings(Duration.ofMinutes(2), 20, Duration.ofMinutes(30)),
                new ManualClock(NOW));
        handler = new OrderPaidHandler(intake);
    }

    private static Map<String, Object> data() {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("channel", "email");
        delivery.put("address", "anna@mail.example");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", ORDER.toString());
        data.put("orderNumber", 1001);
        data.put("buyerId", BUYER.toString());
        data.put("sellerId", "0199e0a0-0000-4000-8000-0000000000b1");
        data.put("productId", "0199e0a0-0000-7000-8000-0000000000c1");
        data.put("productTitle", "Секретная игра: ключ Steam");
        data.put("quantity", 2);
        data.put("paidAt", "2026-10-19T11:59:00.000Z");
        data.put("latePayment", false);
        data.put("delivery", delivery);
        return data;
    }

    private static Map<String, Object> with(String field, Object value) {
        Map<String, Object> data = data();
        data.put(field, value);
        return data;
    }

    private static EventEnvelope event(Map<String, Object> data) {
        return new EventEnvelope(UUID.fromString("0199e0a0-0000-7000-8000-000000000a77"), "order-service", "order.paid", NOW, ORDER.toString(), 1, "Order",
                null, "corr-1", data);
    }

    @Test
    void theHandlerNamesItsTopicTypeAndTheComponent() {
        assertEquals("delivery-domain", handler.name());
        assertEquals("order.events", handler.topic());
        assertEquals(Set.of("order.paid"), handler.types());
        assertEquals(1, handler.maxSchemaVersion());
    }

    @Test
    void thePaidEventCreatesTheQueuedDeliveryAndTheWatch() {
        HandleResult result = handler.handle(event(data()));

        assertTrue(result.applied());
        assertEquals(1, deliveries.rows().size());
        FakeDeliveries.Row row = deliveries.rows().get(0);
        assertEquals("queued", row.status);
        assertEquals("anna@mail.example", row.address);
        assertEquals("Секретная игра: ключ Steam", row.title);
        assertEquals(2, row.quantity);
        assertEquals(BUYER, row.buyerId);
        assertEquals(Instant.parse("2026-10-19T12:29:00Z"), deliveries.watches().get(ORDER), "30 минут от оплаты");
    }

    @Test
    void theSameOrderUnderAnotherEventIdCreatesNoSecondDelivery() {
        handler.handle(event(data()));

        HandleResult second = handler.handle(new EventEnvelope(UUID.fromString("0199e0a0-0000-7000-8000-000000000a78"), "order-service", "order.paid", NOW,
                ORDER.toString(), 1, "Order", null, "corr-2", data()));

        assertFalse(second.applied());
        assertEquals("delivery-exists", second.reason());
        assertEquals(1, deliveries.rows().size());
    }

    @Test
    void aLatePaymentIsHandledLikeAnyOther() {
        assertTrue(handler.handle(event(with("latePayment", true))).applied());
    }

    @Test
    void anEventNotMatchingTheSchemaIsFatalAndCreatesNothing() {
        Map<String, Object> noAddress = data();
        noAddress.remove("delivery");
        Map<String, Object> badChannel = data();
        badChannel.put("delivery", Map.of("channel", "sms", "address", "+79990000000"));
        Map<String, Object> shortAddress = data();
        shortAddress.put("delivery", Map.of("channel", "email", "address", "a@"));
        Map<String, Object> noAddressText = data();
        noAddressText.put("delivery", Map.of("channel", "email", "address", 7));
        Map<String, Object> upperOrder = with("orderId", ORDER.toString().toUpperCase());
        Map<String, Object> noTitle = with("productTitle", "");
        Map<String, Object> longTitle = with("productTitle", "я".repeat(201));
        Map<String, Object> zeroQuantity = with("quantity", 0);
        Map<String, Object> hugeQuantity = with("quantity", 11);
        Map<String, Object> fractionalQuantity = with("quantity", 1.5);
        Map<String, Object> badTime = with("paidAt", "вчера");
        Map<String, Object> noTime = new LinkedHashMap<>(data());
        noTime.remove("paidAt");
        Map<String, Object> noBuyer = new LinkedHashMap<>(data());
        noBuyer.remove("buyerId");
        for (Map<String, Object> broken : java.util.List.of(noAddress, badChannel, shortAddress, noAddressText, upperOrder, noTitle, longTitle, zeroQuantity,
                hugeQuantity, fractionalQuantity, badTime, noTime, noBuyer)) {
            FatalEventException e = assertThrows(FatalEventException.class, () -> handler.handle(event(broken)), broken.toString());

            assertFalse(String.valueOf(e.getMessage()).contains("anna@mail.example"), "в тексте ошибки нет адреса");
            assertFalse(String.valueOf(e.getMessage()).contains("Секретная игра"), "в тексте ошибки нет названия товара");
        }
        assertTrue(deliveries.rows().isEmpty());
        assertTrue(deliveries.watches().isEmpty());
    }
}
