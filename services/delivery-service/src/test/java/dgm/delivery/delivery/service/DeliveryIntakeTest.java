package dgm.delivery.delivery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.kit.time.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Приём оплаты (TC-602, SM-06/T1, INV-18): первичная выдача «в очереди» со снимком адреса и контроль доставки с крайним сроком от момента
 * оплаты. Повтор по тому же заказу второй выдачи и второго контроля не создаёт.
 */
@Tag("TC-602")
class DeliveryIntakeTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");

    private FakeDeliveries deliveries;
    private ManualClock clock;
    private DeliveryIntake intake;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        clock = new ManualClock(NOW);
        intake = new DeliveryIntake(deliveries, new DeliverySettings(Duration.ofMinutes(2), 20, Duration.ofMinutes(30)), clock);
    }

    @Test
    void thePaidOrderGetsAQueuedPrimaryDeliveryDueAtOnceWithTheSnapshotOfTheAddress() {
        assertTrue(intake.accept(ORDER, BUYER, "anna@mail.example", "Игра", 2, NOW.minusSeconds(3)));

        assertEquals(1, deliveries.rows().size());
        FakeDeliveries.Row row = deliveries.rows().get(0);
        assertEquals("primary", row.kind);
        assertEquals("queued", row.status);
        assertEquals(ORDER, row.orderId);
        assertEquals(BUYER, row.buyerId);
        assertEquals("anna@mail.example", row.address);
        assertEquals("Игра", row.title);
        assertEquals(2, row.quantity);
        assertEquals(0, row.attemptNo);
        assertEquals(NOW, row.nextAttemptAt, "первая попытка сразу");
    }

    @Test
    void theControlDeadlineIsCountedFromThePaymentNotFromTheArrivalOfTheEvent() {
        Instant paidAt = NOW.minus(Duration.ofMinutes(10));

        intake.accept(ORDER, BUYER, "anna@mail.example", "Игра", 1, paidAt);

        assertEquals(paidAt.plus(Duration.ofMinutes(30)), deliveries.watches().get(ORDER));
    }

    @Test
    void theSecondEventOfTheSameOrderCreatesNeitherAnotherDeliveryNorAnotherWatch() {
        assertTrue(intake.accept(ORDER, BUYER, "anna@mail.example", "Игра", 1, NOW));
        clock.advance(Duration.ofMinutes(5));

        assertFalse(intake.accept(ORDER, BUYER, "other@mail.example", "Другое", 3, NOW.plusSeconds(300)));

        assertEquals(1, deliveries.rows().size());
        assertEquals("anna@mail.example", deliveries.rows().get(0).address, "снимок первого события не заменяется");
        assertEquals(NOW.plus(Duration.ofMinutes(30)), deliveries.watches().get(ORDER));
        assertEquals(1, deliveries.watches().size());
    }

    @Test
    void differentOrdersGetTheirOwnDeliveriesAndWatches() {
        UUID other = UUID.fromString("0199e0a0-0000-7000-8000-000000000302");

        assertTrue(intake.accept(ORDER, BUYER, "anna@mail.example", "Игра", 1, NOW));
        assertTrue(intake.accept(other, BUYER, "anna@mail.example", "Игра", 1, NOW));

        assertEquals(2, deliveries.rows().size());
        assertEquals(2, deliveries.watches().size());
        assertFalse(deliveries.rows().get(0).id.equals(deliveries.rows().get(1).id));
    }
}
