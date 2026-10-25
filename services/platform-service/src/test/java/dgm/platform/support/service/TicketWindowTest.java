package dgm.platform.support.service;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.support.domain.OrderView;
import dgm.platform.support.testing.SupportWorld;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Окно обращения (INV-22, US-7.1, сценарий 3): от первичной выдачи по часам сервиса, граница на значениях 71:59:59, 72:00:00 и 72:00:01,
 * повторная отправка окно не продлевает, значение читается из параметра {@code support.window-seconds}.
 */
@Tag("TC-702")
class TicketWindowTest {

    private static final Duration WINDOW = Duration.ofHours(72);

    private SupportWorld world;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
    }

    private UUID issuedAgo(Duration age) {
        return world.issuedOrder(BUYER, T0.minus(age));
    }

    @Test
    void theBoundaryIsInclusiveAtSeventyTwoHours() {
        assertEquals("created", world.tickets.create(BUYER, issuedAgo(WINDOW.minusSeconds(1))).status(), "71:59:59 принимается");
        assertEquals("created", world.tickets.create(BUYER, issuedAgo(WINDOW)).status(), "ровно 72:00:00 принимается");
    }

    @Test
    void afterSeventyTwoHoursTheTicketIsRefusedAndNothingIsWritten() {
        UUID late = issuedAgo(WINDOW.plusSeconds(1));

        ProblemException refused = assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, late));

        assertEquals(ProblemType.TICKET_WINDOW_CLOSED, refused.type());
        assertEquals(0, world.repository.ticketsOf(late).size());
        assertEquals(0, world.outbox.events("ticket.created").size());
        assertEquals(0, world.limiter.calls(), "отказ по окну счётчик лимита не тратит");
        assertEquals(1, world.count("window-closed"));
    }

    @Test
    void theTimeIsCountedByTheServiceClockNotByTheModelRead() {
        UUID order = issuedAgo(WINDOW.minusSeconds(10));
        world.clock.advance(Duration.ofSeconds(11));

        ProblemException refused = assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, order));

        assertEquals(ProblemType.TICKET_WINDOW_CLOSED, refused.type(), "через 11 секунд окно закрылось, хотя модель чтения не менялась");
    }

    @Test
    void aSecondIssueDoesNotProlongTheWindow() {
        UUID order = issuedAgo(WINDOW.plusSeconds(1));
        OrderView before = world.repository.findOrderView(order).orElseThrow();
        // Повторная отправка ключа создаёт новую выдачу и событие order.issued с новым временем: первое время остаётся (INV-17)
        world.events.onOrderIssued(order, before.orderNumber(), BUYER, T0);

        assertEquals(before.issuedAt(), world.repository.findOrderView(order).orElseThrow().issuedAt());
        ProblemException refused = assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, order));
        assertEquals(ProblemType.TICKET_WINDOW_CLOSED, refused.type());
    }

    @Test
    void theWindowComesFromTheParameter() {
        UUID order = issuedAgo(Duration.ofHours(2));
        world.parameters.set(TicketService.WINDOW_KEY, 3600);

        ProblemException refused = assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, order));
        assertEquals(ProblemType.TICKET_WINDOW_CLOSED, refused.type(), "окно в час, выдача два часа назад");

        world.parameters.set(TicketService.WINDOW_KEY, 3 * 3600);
        assertEquals("created", world.tickets.create(BUYER, order).status(), "окно расширено без перезапуска");
    }
}
