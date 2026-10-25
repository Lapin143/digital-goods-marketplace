package dgm.platform.support.service;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.OPERATOR;
import static dgm.platform.support.testing.SupportWorld.STRANGER;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.platform.support.domain.Operator;
import dgm.platform.support.domain.QueueFilter;
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.domain.StaffTicketPage;
import dgm.platform.support.testing.SupportWorld;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Очередь оператора (US-7.2, Р-163): обращения двух источников вместе, старые выше, статус и оператор как фильтры, курсор. Значений ключей
 * и адресов в строке очереди нет (INV-23): в {@link StaffTicket} для них нет полей.
 */
@Tag("TC-704")
class QueueListingTest {

    private SupportWorld world;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
    }

    private UUID issued() {
        return world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)));
    }

    private static QueueFilter queue(int limit) {
        return new QueueFilter(List.of("created"), Optional.empty(), OptionalLong.empty(), limit, Optional.empty());
    }

    @Test
    void queueHoldsTicketsOfBothSourcesOldestFirst() {
        UUID first = issued();
        UUID buyerTicket = world.tickets.create(BUYER, first).id();
        world.clock.advance(Duration.ofMinutes(5));
        UUID second = issued();
        UUID systemTicket = world.overdueTicketOf(second);

        StaffTicketPage page = world.queue.list(queue(20));

        assertEquals(List.of(buyerTicket, systemTicket), page.items().stream().map(StaffTicket::id).toList(), "старые выше новых");
        StaffTicket fromBuyer = page.items().get(0);
        assertEquals("buyer", fromBuyer.source());
        assertEquals("key_not_received", fromBuyer.reason());
        assertEquals(first, fromBuyer.orderId());
        assertEquals(1000, fromBuyer.orderNumber());
        assertEquals("issued", fromBuyer.orderStatus());
        assertEquals(Optional.of(T0.minus(Duration.ofHours(1))), fromBuyer.issuedAt());
        assertEquals(T0, fromBuyer.createdAt());
        assertEquals(Optional.empty(), fromBuyer.operatorId());
        assertEquals(Optional.empty(), fromBuyer.takenAt());
        StaffTicket fromSystem = page.items().get(1);
        assertEquals("system", fromSystem.source());
        assertEquals("delivery_overdue", fromSystem.reason());
        assertEquals(1001, fromSystem.orderNumber());
        assertTrue(page.next().isEmpty());
    }

    @Test
    void queueShowsOnlyTheStatusesAsked() {
        UUID taken = world.tickets.create(BUYER, issued()).id();
        UUID waiting = world.tickets.create(BUYER, issued()).id();
        world.queue.take(new Operator(OPERATOR, "203.0.113.7"), taken);

        assertEquals(List.of(waiting), world.queue.list(queue(20)).items().stream().map(StaffTicket::id).toList(), "в очереди только «создано»");

        StaffTicketPage mine = world.queue.list(new QueueFilter(List.of("in_progress"), Optional.of(OPERATOR), OptionalLong.empty(), 20, Optional.empty()));
        assertEquals(List.of(taken), mine.items().stream().map(StaffTicket::id).toList());
        assertEquals(Optional.of(OPERATOR), mine.items().get(0).operatorId());
        assertEquals(Optional.of(T0), mine.items().get(0).takenAt());

        StaffTicketPage others = world.queue.list(new QueueFilter(List.of("in_progress"), Optional.of(STRANGER), OptionalLong.empty(), 20, Optional.empty()));
        assertTrue(others.items().isEmpty(), "чужие обращения в работе не попадают в выборку другого оператора");
    }

    @Test
    void pagesKeepTheOrderAndTheCursorEndsOnTheLastPage() {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(world.tickets.create(BUYER, issued()).id());
            world.clock.advance(Duration.ofSeconds(10));
        }

        List<UUID> seen = new ArrayList<>();
        Optional<QueueFilter.After> after = Optional.empty();
        int pages = 0;
        do {
            StaffTicketPage page = world.queue.list(new QueueFilter(List.of("created"), Optional.empty(), OptionalLong.empty(), 2, after));
            page.items().forEach(t -> seen.add(t.id()));
            after = page.next();
            pages++;
        } while (after.isPresent());

        assertEquals(expected, seen, "каждое обращение ровно один раз, старые выше");
        assertEquals(3, pages);
    }

    @Test
    void ticketsWithTheSameTimeAreOrderedByIdentifier() {
        UUID a = world.tickets.create(BUYER, issued()).id();
        UUID b = world.tickets.create(BUYER, issued()).id();

        List<UUID> ids = world.queue.list(queue(20)).items().stream().map(StaffTicket::id).toList();

        assertEquals(a.compareTo(b) < 0 ? List.of(a, b) : List.of(b, a), ids, "при равном времени создания порядок по идентификатору");
    }

    @Test
    void solvedTicketsAreOutOfTheQueue() {
        UUID order = issued();
        world.tickets.create(BUYER, order);
        world.events.onDeliveryDelivered(order, T0.plusSeconds(5));

        assertTrue(world.queue.list(queue(20)).items().isEmpty());
    }
}
