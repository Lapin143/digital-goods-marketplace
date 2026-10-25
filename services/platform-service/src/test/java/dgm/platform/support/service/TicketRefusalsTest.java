package dgm.platform.support.service;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.STRANGER;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.support.testing.SupportWorld;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Отказы при создании обращения (US-7.1, Р-177): чужой и несуществующий заказ неразличимы, заказ не «выдан», лимит частоты с
 * {@code Retry-After}, лимит читается из параметра, 409 лимит не тратят, а при 429 обращение не остаётся.
 */
@Tag("TC-703")
class TicketRefusalsTest {

    private SupportWorld world;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
    }

    private UUID issued() {
        return world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)));
    }

    @Test
    void foreignAndMissingOrdersGiveTheSameAnswer() {
        UUID foreign = world.issuedOrder(STRANGER, T0.minus(Duration.ofHours(1)));

        ProblemException notOwned = assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, foreign));
        ProblemException missing = assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, UUID.randomUUID()));

        assertEquals(ProblemType.NOT_FOUND, notOwned.type());
        assertEquals(ProblemType.NOT_FOUND, missing.type());
        assertEquals(missing.getMessage(), notOwned.getMessage(), "доступ по владельцу, а не по номеру: ответы совпадают");
        assertEquals(0, world.repository.ticketsOf(foreign).size());
        assertEquals(2, world.count("not-found"));
    }

    @Test
    void anOrderThatIsNotIssuedIsAStateConflict() {
        UUID paid = world.paidOrder(BUYER);

        ProblemException refused = assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, paid));

        assertEquals(ProblemType.STATE_CONFLICT, refused.type());
        assertEquals(0, world.repository.ticketsOf(paid).size());
    }

    @Test
    void theSixthTicketInAnHourGetsRetryAfterAndLeavesNothingBehind() {
        for (int i = 0; i < 5; i++) {
            world.clock.advance(Duration.ofMinutes(1));
            world.tickets.create(BUYER, issued());
        }
        UUID sixth = issued();
        world.clock.advance(Duration.ofMinutes(1));

        ProblemException limited = assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, sixth));

        assertEquals(ProblemType.RATE_LIMITED, limited.type());
        long retry = Long.parseLong(limited.headers().get("Retry-After"));
        assertEquals(55 * 60, retry, "окно освободится, когда истечёт час первого обращения: прошло 6 минут, первое было минутой позже старта");
        assertEquals(0, world.repository.ticketsOf(sixth).size(), "вставка откатилась вместе с транзакцией");
        assertEquals(5, world.outbox.events("ticket.created").size(), "события у отказа нет");
        assertEquals(1, world.count("rate-limited"));
    }

    @Test
    void theHourPassesAndTheBuyerMayAskAgain() {
        for (int i = 0; i < 5; i++) {
            world.tickets.create(BUYER, issued());
        }
        UUID next = issued();
        assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, next));

        world.clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertEquals("created", world.tickets.create(BUYER, next).status());
    }

    @Test
    void conflictsDoNotSpendTheLimitAndAnotherBuyerHasItsOwn() {
        UUID order = issued();
        world.tickets.create(BUYER, order);
        for (int i = 0; i < 8; i++) {
            assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, order));
        }
        assertEquals(1, world.limiter.calls(), "счётчик спрашивали только при настоящей вставке");

        for (int i = 0; i < 4; i++) {
            world.tickets.create(BUYER, issued());
        }
        UUID other = world.issuedOrder(STRANGER, T0.minus(Duration.ofHours(1)));
        assertEquals("created", world.tickets.create(STRANGER, other).status(), "лимит у каждого покупателя свой");
    }

    @Test
    void theLimitComesFromTheParameter() {
        world.parameters.set(TicketService.RATE_KEY, 1);
        world.tickets.create(BUYER, issued());

        ProblemException limited = assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, issued()));
        assertEquals(ProblemType.RATE_LIMITED, limited.type());
        assertTrue(Long.parseLong(limited.headers().get("Retry-After")) > 0);

        world.parameters.set(TicketService.RATE_KEY, 3);
        assertEquals("created", world.tickets.create(BUYER, issued()).status(), "лимит поднят без перезапуска");
    }
}
