package dgm.order.orders;

import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderFilter;
import dgm.order.orders.repository.OrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий в памяти для модульных тестов. Хранит заказы по покупателям и ведёт выборку как SQL: только свои заказы, статусы, период,
 * порядок «новые выше» и позиция курсора. Последнее условие выборки запоминается.
 */
public final class FakeOrders implements OrderRepository {

    private static final Comparator<Order> NEWEST_FIRST = Comparator.comparing(Order::createdAt).thenComparing(Order::id).reversed();

    private final Map<UUID, List<Order>> byBuyer = new HashMap<>();
    private OrderFilter lastFilter;

    public static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", n));
    }

    public static Order order(int n, String status, Instant createdAt) {
        return new Order(id(n), 1000L + n, id(1000 + n), "Товар " + n, 1, 1_000_00L, 1_000_00L, "RUB", status, Optional.empty(), "email", "buyer@mail.example",
                Optional.empty(), Optional.empty(), createdAt, Optional.empty(), Optional.empty());
    }

    public FakeOrders with(UUID buyer, Order... orders) {
        byBuyer.computeIfAbsent(buyer, b -> new ArrayList<>()).addAll(List.of(orders));
        return this;
    }

    public OrderFilter lastFilter() {
        return lastFilter;
    }

    @Override
    public List<Order> findByBuyer(OrderFilter filter) {
        lastFilter = filter;
        return byBuyer.getOrDefault(filter.buyerId(), List.of()).stream()
                .filter(o -> filter.statuses().map(s -> s.contains(o.status())).orElse(true))
                .filter(o -> filter.createdFrom().map(f -> !o.createdAt().isBefore(f)).orElse(true))
                .filter(o -> filter.createdTo().map(t -> o.createdAt().isBefore(t)).orElse(true))
                .sorted(NEWEST_FIRST)
                .filter(o -> filter.after().map(a -> before(o, a)).orElse(true))
                .limit(filter.limit() + 1L)
                .toList();
    }

    private static boolean before(Order order, OrderFilter.After after) {
        int byTime = order.createdAt().compareTo(after.createdAt());
        return byTime < 0 || (byTime == 0 && order.id().compareTo(after.id()) < 0);
    }
}
