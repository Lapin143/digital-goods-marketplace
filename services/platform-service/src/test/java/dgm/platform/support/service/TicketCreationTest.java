package dgm.platform.support.service;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.STRANGER;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.support.domain.SupportTicket;
import dgm.platform.support.domain.TicketFilter;
import dgm.platform.support.domain.TicketPage;
import dgm.platform.support.testing.RecordingOutbox;
import dgm.platform.support.testing.SupportWorld;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Обращение «Не получил ключ» (SM-07/T1, Р-177): создание по выданному заказу, одно открытое обращение на заказ (INV-21), событие
 * {@code ticket.created} только при настоящей вставке, список и карточка покупателя.
 */
@Tag("TC-701")
class TicketCreationTest {

    private SupportWorld world;
    private UUID order;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
        order = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)));
    }

    @Test
    void buyerTicketIsCreatedWithTheEventAndWithoutAnythingPersonal() {
        SupportTicket ticket = world.tickets.create(BUYER, order);

        assertEquals(order, ticket.orderId());
        assertEquals(BUYER, ticket.buyerId());
        assertEquals("buyer", ticket.source());
        assertEquals("key_not_received", ticket.reason());
        assertEquals("created", ticket.status());
        assertEquals(T0, ticket.createdAt());
        assertTrue(ticket.resolvedAt().isEmpty());
        assertTrue(ticket.resolvedBy().isEmpty());
        assertTrue(ticket.action().isEmpty());

        List<RecordingOutbox.Event> created = world.outbox.events("ticket.created");
        assertEquals(1, created.size());
        RecordingOutbox.Event event = created.get(0);
        assertEquals("support.events", event.topic());
        assertEquals(ticket.id().toString(), event.aggregateId());
        assertEquals(Map.of("ticketId", ticket.id().toString(), "orderId", order.toString(), "buyerId", BUYER.toString(), "source", "buyer",
                "reason", "key_not_received"), event.data(), "в событии только идентификаторы и причина");
        assertEquals(1, world.count("created"));
    }

    @Test
    void secondTicketOnTheSameOrderIsRefusedAndWritesNoEvent() {
        world.tickets.create(BUYER, order);

        ProblemException refused = assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, order));

        assertEquals(ProblemType.TICKET_ALREADY_OPEN, refused.type());
        assertEquals(1, world.repository.ticketsOf(order).size());
        assertEquals(1, world.outbox.events("ticket.created").size(), "событие только у настоящей вставки");
        assertEquals(1, world.count("already-open"));
    }

    @Test
    void openTicketOfTheSystemBlocksTheBuyerToo() {
        world.events.onDeliveryOverdue(order, T0);

        ProblemException refused = assertThrows(ProblemException.class, () -> world.tickets.create(BUYER, order));

        assertEquals(ProblemType.TICKET_ALREADY_OPEN, refused.type());
        assertEquals(1, world.repository.ticketsOf(order).size(), "INV-21 держит и обращение системы");
    }

    @Test
    void aResolvedTicketAllowsANewOne() {
        SupportTicket first = world.tickets.create(BUYER, order);
        world.repository.resolveByOperator(first.id(), "resend", T0);

        SupportTicket second = world.tickets.create(BUYER, order);

        assertEquals(2, world.repository.ticketsOf(order).size());
        assertEquals("created", second.status());
        assertEquals(2, world.outbox.events("ticket.created").size());
    }

    @Test
    void listShowsOnlyOwnTicketsNewestFirstAndPagesByCursor() {
        UUID second = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(2)));
        UUID third = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(3)));
        UUID strangers = world.issuedOrder(STRANGER, T0.minus(Duration.ofHours(1)));
        SupportTicket a = world.tickets.create(BUYER, order);
        world.clock.advance(Duration.ofMinutes(1));
        SupportTicket b = world.tickets.create(BUYER, second);
        world.clock.advance(Duration.ofMinutes(1));
        SupportTicket c = world.tickets.create(BUYER, third);
        world.tickets.create(STRANGER, strangers);

        TicketPage first = world.tickets.list(new TicketFilter(BUYER, Optional.empty(), List.of(), 2, Optional.empty()));
        assertEquals(List.of(c.id(), b.id()), first.items().stream().map(SupportTicket::id).toList());
        assertTrue(first.next().isPresent());

        TicketPage rest = world.tickets.list(new TicketFilter(BUYER, Optional.empty(), List.of(), 2, first.next()));
        assertEquals(List.of(a.id()), rest.items().stream().map(SupportTicket::id).toList());
        assertTrue(rest.next().isEmpty(), "на последней странице курсора нет");
    }

    @Test
    void listFiltersByOrderAndByStatus() {
        UUID other = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(2)));
        SupportTicket open = world.tickets.create(BUYER, order);
        SupportTicket done = world.tickets.create(BUYER, other);
        world.repository.resolveByOperator(done.id(), "resend", T0);

        TicketPage byOrder = world.tickets.list(new TicketFilter(BUYER, Optional.of(order), List.of(), 20, Optional.empty()));
        assertEquals(List.of(open.id()), byOrder.items().stream().map(SupportTicket::id).toList());

        TicketPage resolved = world.tickets.list(new TicketFilter(BUYER, Optional.empty(), List.of("resolved"), 20, Optional.empty()));
        assertEquals(List.of(done.id()), resolved.items().stream().map(SupportTicket::id).toList());
        assertEquals("operator", resolved.items().get(0).resolvedBy().orElseThrow());
        assertEquals("resend", resolved.items().get(0).action().orElseThrow());

        TicketPage both = world.tickets.list(new TicketFilter(BUYER, Optional.empty(), List.of("created", "resolved"), 20, Optional.empty()));
        assertEquals(2, both.items().size());
    }

    @Test
    void cardIsOwnedByTheBuyerOnly() {
        SupportTicket ticket = world.tickets.create(BUYER, order);

        assertEquals(ticket, world.tickets.get(BUYER, ticket.id()));
        ProblemException foreign = assertThrows(ProblemException.class, () -> world.tickets.get(STRANGER, ticket.id()));
        ProblemException missing = assertThrows(ProblemException.class, () -> world.tickets.get(STRANGER, UUID.randomUUID()));
        assertEquals(ProblemType.NOT_FOUND, foreign.type());
        assertEquals(missing.type(), foreign.type());
        assertEquals(missing.getMessage(), foreign.getMessage(), "чужое обращение неотличимо от несуществующего (U1)");
    }
}
