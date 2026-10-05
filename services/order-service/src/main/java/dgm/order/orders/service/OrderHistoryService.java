package dgm.order.orders.service;

import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderFilter;
import dgm.order.orders.domain.OrderPage;
import dgm.order.orders.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** История заказов покупателя (OP-58, US-5.9). Владельца задаёт вызывающий из токена, сервис другого владельца не подставляет. */
@Component
public final class OrderHistoryService {

    private final OrderRepository orders;

    public OrderHistoryService(OrderRepository orders) {
        this.orders = orders;
    }

    public OrderPage list(OrderFilter filter) {
        List<Order> rows = orders.findByBuyer(filter);
        boolean more = rows.size() > filter.limit();
        List<Order> items = more ? rows.subList(0, filter.limit()) : rows;
        Optional<OrderFilter.After> next = more
                ? Optional.of(new OrderFilter.After(items.get(items.size() - 1).createdAt(), items.get(items.size() - 1).id()))
                : Optional.empty();
        return new OrderPage(List.copyOf(items), filter.limit(), next);
    }
}
