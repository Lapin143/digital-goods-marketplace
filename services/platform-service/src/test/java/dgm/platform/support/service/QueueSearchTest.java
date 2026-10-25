package dgm.platform.support.service;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.STRANGER;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.platform.support.domain.QueueFilter;
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.testing.SupportWorld;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Поиск обращения по короткому номеру заказа (US-7.2, сценарий 6, Р-163): номер называет покупатель, оператор находит заказ и его обращения
 * по проекции {@code support.order_view}. Неизвестный номер даёт пустую страницу, подсказок о других заказах нет.
 */
@Tag("TC-707")
class QueueSearchTest {

    private SupportWorld world;
    private UUID firstOrder;
    private UUID secondOrder;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
        firstOrder = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)));
        secondOrder = world.issuedOrder(STRANGER, T0.minus(Duration.ofHours(1)));
    }

    private List<StaffTicket> search(long number, String... statuses) {
        return world.queue.list(new QueueFilter(List.of(statuses), Optional.empty(), OptionalLong.of(number), 20, Optional.empty())).items();
    }

    @Test
    void theNumberFindsTheTicketsOfThatOrderOnly() {
        UUID mine = world.tickets.create(BUYER, firstOrder).id();
        world.tickets.create(STRANGER, secondOrder);

        List<StaffTicket> found = search(1000);

        assertEquals(List.of(mine), found.stream().map(StaffTicket::id).toList());
        assertEquals(1000, found.get(0).orderNumber());
        assertEquals(firstOrder, found.get(0).orderId());
    }

    @Test
    void anUnknownNumberGivesAnEmptyPageWithoutHints() {
        world.tickets.create(BUYER, firstOrder);
        world.tickets.create(STRANGER, secondOrder);

        assertTrue(search(424242).isEmpty());
        assertTrue(world.queue.list(new QueueFilter(List.of(), Optional.empty(), OptionalLong.of(424242), 20, Optional.empty())).next().isEmpty());
    }

    @Test
    void withoutAStatusTheSearchSeesSolvedTicketsToo() {
        UUID ticket = world.tickets.create(BUYER, firstOrder).id();
        world.events.onDeliveryDelivered(firstOrder, T0.plusSeconds(30));

        assertEquals(List.of(ticket), search(1000).stream().map(StaffTicket::id).toList(), "без status: обращения заказа с любым статусом");
        assertTrue(search(1000, "created").isEmpty(), "с status=created решённое не показывается");
        assertEquals(List.of(ticket), search(1000, "resolved").stream().map(StaffTicket::id).toList());
    }

    @Test
    void anOrderWithoutTicketsGivesAnEmptyPage() {
        assertTrue(search(1001).isEmpty());
    }
}
