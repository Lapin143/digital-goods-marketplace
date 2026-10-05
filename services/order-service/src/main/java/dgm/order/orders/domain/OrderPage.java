package dgm.order.orders.domain;

import java.util.List;
import java.util.Optional;

/** Страница истории заказов и позиция следующей (пусто на последней странице). */
public record OrderPage(List<Order> items, int limit, Optional<OrderFilter.After> next) {
}
