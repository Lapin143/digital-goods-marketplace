package dgm.order.orders.domain;

import java.util.List;
import java.util.Optional;

/** Страница заказов продавца и позиция следующей (пусто на последней странице). */
public record SellerOrderPage(List<SellerOrder> items, int limit, Optional<OrderFilter.After> next) {
}
