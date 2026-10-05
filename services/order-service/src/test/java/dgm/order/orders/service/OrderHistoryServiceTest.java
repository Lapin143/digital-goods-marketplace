package dgm.order.orders.service;

import static dgm.order.orders.FakeOrders.id;
import static dgm.order.orders.FakeOrders.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.order.orders.FakeOrders;
import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderFilter;
import dgm.order.orders.domain.OrderPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** История заказов: страницы, курсор и условие U1 (чужие заказы в выборку не попадают). */
class OrderHistoryServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final UUID ALICE = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID BOB = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");

    private static OrderFilter filter(UUID buyer, int limit, OrderFilter.After after) {
        return new OrderFilter(buyer, Optional.empty(), Optional.empty(), Optional.empty(), limit, Optional.ofNullable(after));
    }

    private static FakeOrders data() {
        return new FakeOrders()
                .with(ALICE, order(1, "issued", T0), order(2, "awaiting_payment", T0.plusSeconds(60)), order(3, "cancelled", T0.plusSeconds(120)))
                .with(BOB, order(4, "paid", T0.plusSeconds(30)));
    }

    private static List<UUID> walk(OrderHistoryService service, UUID buyer, int limit) {
        List<UUID> seen = new ArrayList<>();
        OrderFilter.After after = null;
        int pages = 0;
        do {
            OrderPage page = service.list(filter(buyer, limit, after));
            page.items().forEach(o -> seen.add(o.id()));
            after = page.next().orElse(null);
            pages++;
        } while (after != null && pages < 10);
        return seen;
    }

    @Test
    void buyerSeesOnlyOwnOrdersNewestFirst() {
        OrderPage page = new OrderHistoryService(data()).list(filter(ALICE, 20, null));
        assertEquals(List.of(id(3), id(2), id(1)), page.items().stream().map(Order::id).toList());
        assertTrue(page.next().isEmpty());
    }

    @Test
    void otherBuyerOrdersNeverLeak() {
        OrderPage page = new OrderHistoryService(data()).list(filter(BOB, 20, null));
        assertEquals(List.of(id(4)), page.items().stream().map(Order::id).toList());
    }

    @Test
    void buyerWithoutOrdersGetsEmptyPage() {
        OrderPage page = new OrderHistoryService(data()).list(filter(UUID.fromString("0199e0a0-0000-4000-8000-0000000000ff"), 20, null));
        assertTrue(page.items().isEmpty());
        assertTrue(page.next().isEmpty());
    }

    @Test
    void cursorPointsToLastShownOrder() {
        OrderPage page = new OrderHistoryService(data()).list(filter(ALICE, 2, null));
        assertEquals(2, page.items().size());
        OrderFilter.After next = page.next().orElseThrow();
        assertEquals(id(2), next.id());
        assertEquals(T0.plusSeconds(60), next.createdAt());
    }

    @Test
    void exactlyLimitIsTheLastPage() {
        assertTrue(new OrderHistoryService(data()).list(filter(ALICE, 3, null)).next().isEmpty());
    }

    @Test
    void walkingAllPagesVisitsEveryOrderOnce() {
        assertEquals(List.of(id(3), id(2), id(1)), walk(new OrderHistoryService(data()), ALICE, 1));
    }

    @Test
    void ordersWithTheSameCreationTimeAreNotLostOrRepeated() {
        FakeOrders same = new FakeOrders().with(ALICE, order(1, "paid", T0), order(2, "paid", T0), order(3, "paid", T0));
        assertEquals(List.of(id(3), id(2), id(1)), walk(new OrderHistoryService(same), ALICE, 1));
    }

    @Test
    void statusAndPeriodFiltersAreApplied() {
        FakeOrders orders = data();
        OrderFilter wanted = new OrderFilter(ALICE, Optional.of(List.of("issued", "cancelled")), Optional.of(T0), Optional.of(T0.plusSeconds(121)), 20,
                Optional.empty());
        OrderPage page = new OrderHistoryService(orders).list(wanted);
        assertEquals(List.of(id(3), id(1)), page.items().stream().map(Order::id).toList());
        assertEquals(wanted, orders.lastFilter());
    }

    @Test
    void periodEndIsExclusive() {
        OrderFilter wanted = new OrderFilter(ALICE, Optional.empty(), Optional.empty(), Optional.of(T0.plusSeconds(120)), 20, Optional.empty());
        OrderPage page = new OrderHistoryService(data()).list(wanted);
        assertEquals(List.of(id(2), id(1)), page.items().stream().map(Order::id).toList());
    }
}
