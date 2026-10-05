package dgm.order.orders.repository;

import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderFilter;
import java.util.List;

/** Чтение заказов модуля «заказы»: схема {@code orders}, роль {@code app_orders}. */
public interface OrderRepository {

    /** Заказы покупателя по условиям, новые выше. Возвращает не больше {@code filter.limit() + 1} строк: лишняя показывает, что страница не последняя. */
    List<Order> findByBuyer(OrderFilter filter);
}
