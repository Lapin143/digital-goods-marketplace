package dgm.delivery.delivery.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.delivery.delivery.service.DeliveryIntake;
import dgm.delivery.delivery.service.DeliveryRepeat;
import dgm.delivery.delivery.service.DeliverySettings;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.time.ManualClock;
import java.time.Duration;
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
 * Потребитель {@code order.address-updated} (TC-612, компонент {@code delivery-domain}, SM-06/T1, Р-170): создаёт повторную выдачу на новый адрес,
 * не создаёт её при выдаче в очереди и при отсутствии первичной, контроль 30 минут не открывает, событие не по схеме это неисправимая ошибка
 * без адреса в тексте.
 */
@Tag("TC-612")
class OrderAddressUpdatedHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000401");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID TICKET = UUID.fromString("0199e0a0-0000-7000-8000-000000000501");
    private static final String NEW_ADDRESS = "new-anna@mail.example";

    private FakeDeliveries deliveries;
    private ManualClock clock;
    private OrderAddressUpdatedHandler handler;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        clock = new ManualClock(NOW);
        handler = new OrderAddressUpdatedHandler(new DeliveryRepeat(deliveries, clock));
    }

    /** Первичная выдача заказа, как её создаёт приём оплаты, и контроль 30 минут. */
    private void primary(boolean sent) {
        new DeliveryIntake(deliveries, new DeliverySettings(Duration.ofMinutes(2), 20, Duration.ofMinutes(30)), clock)
                .accept(ORDER, BUYER, "anna@mail.example", "Секретная игра: ключ Steam", 2, NOW.minusSeconds(60));
        if (sent) {
            FakeDeliveries.Row row = deliveries.rows().get(0);
            assertTrue(deliveries.markSent(row.id, 0, NOW.minusSeconds(30), NOW.minusSeconds(29)));
        }
    }

    private static Map<String, Object> data() {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("channel", "email");
        delivery.put("address", NEW_ADDRESS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", ORDER.toString());
        data.put("buyerId", BUYER.toString());
        data.put("ticketId", TICKET.toString());
        data.put("delivery", delivery);
        return data;
    }

    private static Map<String, Object> with(String field, Object value) {
        Map<String, Object> data = data();
        data.put(field, value);
        return data;
    }

    private static Map<String, Object> without(String field) {
        Map<String, Object> data = data();
        data.remove(field);
        return data;
    }

    private static EventEnvelope event(UUID id, Map<String, Object> data) {
        return new EventEnvelope(id, "order-service", "order.address-updated", NOW, ORDER.toString(), 1, "Order", null, "corr-1", data);
    }

    private static EventEnvelope event(Map<String, Object> data) {
        return event(UUID.fromString("0199e0a0-0000-7000-8000-000000000b01"), data);
    }

    @Test
    void theHandlerNamesTheComponentOfThePaidEventAndItsOwnType() {
        assertEquals("delivery-domain", handler.name());
        assertEquals("order.events", handler.topic());
        assertEquals(Set.of("order.address-updated"), handler.types());
        assertEquals(1, handler.maxSchemaVersion());
    }

    @Test
    void theEventCreatesTheQueuedRepeatDeliveryToTheNewAddressAndOpensNoWatch() {
        primary(true);
        Instant deadline = deliveries.watches().get(ORDER);
        clock.advance(Duration.ofMinutes(3));

        HandleResult result = handler.handle(event(data()));

        assertTrue(result.applied());
        assertEquals(2, deliveries.rows().size());
        FakeDeliveries.Row repeat = deliveries.rows().get(1);
        assertEquals("repeat", repeat.kind);
        assertEquals("queued", repeat.status);
        assertEquals(NEW_ADDRESS, repeat.address);
        assertEquals("Секретная игра: ключ Steam", repeat.title, "название и число ключей берутся из первичной выдачи");
        assertEquals(2, repeat.quantity);
        assertEquals(BUYER, repeat.buyerId);
        assertEquals(0, repeat.attemptNo);
        assertEquals(NOW.plus(Duration.ofMinutes(3)), repeat.nextAttemptAt, "первая попытка наступает сразу");
        assertEquals(Map.of(ORDER, deadline), deliveries.watches(), "контроль 30 минут один, повторная выдача нового не открывает");
        FakeDeliveries.Row first = deliveries.rows().get(0);
        assertEquals("sent", first.status);
        assertEquals("anna@mail.example", first.address, "адрес первичной выдачи не меняется");
    }

    @Test
    void aSecondEventWhileTheRepeatIsStillQueuedCreatesNothingAndSaysWhy() {
        primary(true);
        handler.handle(event(data()));

        HandleResult second = handler.handle(event(UUID.fromString("0199e0a0-0000-7000-8000-000000000b02"), with("delivery",
                Map.of("channel", "email", "address", "third@mail.example"))));

        assertFalse(second.applied());
        assertEquals("already-queued", second.reason());
        assertEquals(2, deliveries.rows().size());
        assertEquals(NEW_ADDRESS, deliveries.rows().get(1).address, "адрес из первого события не заменяется");
    }

    @Test
    void aQueuedPrimaryDeliveryBlocksTheRepeatToo() {
        primary(false);

        HandleResult result = handler.handle(event(data()));

        assertFalse(result.applied());
        assertEquals("already-queued", result.reason());
        assertEquals(1, deliveries.rows().size());
    }

    @Test
    void onceTheRepeatIsSentTheNextChangeOfTheAddressCreatesTheNextRepeat() {
        primary(true);
        handler.handle(event(data()));
        FakeDeliveries.Row repeat = deliveries.rows().get(1);
        assertTrue(deliveries.markSent(repeat.id, 0, NOW, NOW.plusSeconds(1)));

        HandleResult next = handler.handle(event(UUID.fromString("0199e0a0-0000-7000-8000-000000000b03"), with("delivery",
                Map.of("channel", "email", "address", "third@mail.example"))));

        assertTrue(next.applied());
        assertEquals(3, deliveries.rows().size());
        assertEquals("third@mail.example", deliveries.rows().get(2).address);
        assertEquals("repeat", deliveries.rows().get(2).kind);
    }

    @Test
    void anOrderWithoutThePrimaryDeliveryIsSkippedWithTheReason() {
        HandleResult result = handler.handle(event(data()));

        assertFalse(result.applied());
        assertEquals("no-primary", result.reason());
        assertTrue(deliveries.rows().isEmpty());
        assertTrue(deliveries.watches().isEmpty());
    }

    @Test
    void anotherOrdersDeliveryIsNotTouched() {
        primary(true);
        UUID other = UUID.fromString("0199e0a0-0000-7000-8000-000000000402");

        HandleResult result = handler.handle(event(with("orderId", other.toString())));

        assertEquals("no-primary", result.reason());
        assertEquals(1, deliveries.rows().size());
    }

    @Test
    void anEventNotMatchingTheSchemaIsFatalAndCreatesNothing() {
        primary(true);
        Map<String, Object> badChannel = with("delivery", Map.of("channel", "sms", "address", "+79990000000"));
        Map<String, Object> shortAddress = with("delivery", Map.of("channel", "email", "address", "a@"));
        Map<String, Object> longAddress = with("delivery", Map.of("channel", "email", "address", "a".repeat(255)));
        Map<String, Object> noAddressText = with("delivery", Map.of("channel", "email", "address", 7));
        List<Map<String, Object>> broken = List.of(without("delivery"), without("orderId"), without("buyerId"), without("ticketId"), badChannel, shortAddress,
                longAddress, noAddressText, with("orderId", ORDER.toString().toUpperCase()), with("ticketId", "обращение"), with("buyerId", 5));
        for (Map<String, Object> data : broken) {
            FatalEventException e = assertThrows(FatalEventException.class, () -> handler.handle(event(data)), data.toString());

            assertFalse(String.valueOf(e.getMessage()).contains(NEW_ADDRESS), "в тексте ошибки нет адреса");
        }
        assertEquals(1, deliveries.rows().size(), "кроме первичной выдачи ничего не создано");
    }
}
