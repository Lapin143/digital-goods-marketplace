package dgm.order.orders.service;

import static dgm.order.orders.FakeOrders.id;
import static dgm.order.orders.FakeOrders.sellerOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.order.orders.FakeOrders;
import dgm.order.orders.domain.OrderFilter;
import dgm.order.orders.domain.SellerOrder;
import dgm.order.orders.domain.SellerOrderFilter;
import dgm.order.orders.domain.SellerOrderPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Заказы продавца: страницы, курсор, фильтры и условие U3 (чужие заказы в выборку не попадают ни при каком фильтре). */
@Tag("TC-961")
class SellerOrderServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final UUID ANNA = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID IVAN = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b2");

    private static SellerOrderFilter filter(UUID seller, int limit, OrderFilter.After after) {
        return new SellerOrderFilter(seller, Optional.empty(), Optional.empty(), Optional.empty(), limit, Optional.ofNullable(after));
    }

    private static FakeOrders data() {
        return new FakeOrders()
                .withSeller(ANNA, sellerOrder(1, "issued", T0), sellerOrder(2, "paid", T0.plusSeconds(60)), sellerOrder(3, "cancelled", T0.plusSeconds(120)))
                .withSeller(IVAN, sellerOrder(4, "paid", T0.plusSeconds(30)));
    }

    private static List<UUID> walk(SellerOrderService service, UUID seller, int limit) {
        List<UUID> seen = new ArrayList<>();
        OrderFilter.After after = null;
        int pages = 0;
        do {
            SellerOrderPage page = service.list(filter(seller, limit, after));
            page.items().forEach(o -> seen.add(o.id()));
            after = page.next().orElse(null);
            assertTrue(++pages < 10, "курсор зациклился");
        } while (after != null);
        return seen;
    }

    @Test
    void ordersComeNewestFirstAndTheLastPageHasNoCursor() {
        SellerOrderPage page = new SellerOrderService(data()).list(filter(ANNA, 20, null));

        assertEquals(List.of(id(3), id(2), id(1)), page.items().stream().map(SellerOrder::id).toList());
        assertEquals(20, page.limit());
        assertTrue(page.next().isEmpty());
    }

    @Test
    void theExtraRowOfTheRepositoryTellsThatThereIsANextPageAndIsNotShown() {
        SellerOrderPage page = new SellerOrderService(data()).list(filter(ANNA, 2, null));

        assertEquals(List.of(id(3), id(2)), page.items().stream().map(SellerOrder::id).toList());
        assertEquals(Optional.of(new OrderFilter.After(T0.plusSeconds(60), id(2))), page.next());
    }

    @Test
    void walkingByTheCursorVisitsEveryOrderOnceForAnyPageSize() {
        SellerOrderService service = new SellerOrderService(data());
        for (int limit = 1; limit <= 4; limit++) {
            assertEquals(List.of(id(3), id(2), id(1)), walk(service, ANNA, limit), "лимит " + limit);
        }
    }

    @Test
    void anotherSellerNeverSeesTheOrdersOfTheFirstWhateverTheFilter() {
        FakeOrders orders = data();
        SellerOrderService service = new SellerOrderService(orders);

        assertEquals(List.of(id(4)), walk(service, IVAN, 20));
        SellerOrderFilter all = new SellerOrderFilter(IVAN, Optional.of(List.of("issued", "paid", "cancelled")), Optional.of(T0.minusSeconds(3600)),
                Optional.of(T0.plusSeconds(3600)), 20, Optional.empty());
        assertEquals(List.of(id(4)), service.list(all).items().stream().map(SellerOrder::id).toList());
        assertEquals(IVAN, orders.lastSellerFilter().sellerId());
        assertEquals(List.of(), service.list(filter(UUID.randomUUID(), 20, null)).items());
    }

    @Test
    void statusAndPeriodFiltersNarrowTheSelectionAndThePeriodEndIsExclusive() {
        SellerOrderService service = new SellerOrderService(data());

        SellerOrderFilter byStatus = new SellerOrderFilter(ANNA, Optional.of(List.of("issued", "cancelled")), Optional.empty(), Optional.empty(), 20,
                Optional.empty());
        assertEquals(List.of(id(3), id(1)), service.list(byStatus).items().stream().map(SellerOrder::id).toList());

        SellerOrderFilter byPeriod = new SellerOrderFilter(ANNA, Optional.empty(), Optional.of(T0.plusSeconds(60)), Optional.of(T0.plusSeconds(120)), 20,
                Optional.empty());
        assertEquals(List.of(id(2)), service.list(byPeriod).items().stream().map(SellerOrder::id).toList(), "начало включено, конец не включён");
    }
}
