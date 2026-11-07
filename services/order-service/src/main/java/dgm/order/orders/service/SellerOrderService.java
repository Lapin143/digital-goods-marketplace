package dgm.order.orders.service;

import dgm.order.orders.domain.OrderFilter;
import dgm.order.orders.domain.SellerOrder;
import dgm.order.orders.domain.SellerOrderFilter;
import dgm.order.orders.domain.SellerOrderPage;
import dgm.order.orders.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Заказы продавца ({@code listSellerOrders}, компонент {@code seller-order-reader}, OP-100). Продавца задаёт вызывающий из токена или заголовка шлюза,
 * сервис другого продавца не подставляет (U3). Читает под ролью {@code app_orders_read}, как история покупателя: запись база этой роли отвергает.
 */
@Component
public final class SellerOrderService {

    private final OrderRepository orders;

    public SellerOrderService(OrderRepository orders) {
        this.orders = orders;
    }

    public SellerOrderPage list(SellerOrderFilter filter) {
        List<SellerOrder> rows = orders.findBySeller(filter);
        boolean more = rows.size() > filter.limit();
        List<SellerOrder> items = more ? rows.subList(0, filter.limit()) : rows;
        Optional<OrderFilter.After> next = more
                ? Optional.of(new OrderFilter.After(items.get(items.size() - 1).createdAt(), items.get(items.size() - 1).id()))
                : Optional.empty();
        return new SellerOrderPage(List.copyOf(items), filter.limit(), next);
    }
}
