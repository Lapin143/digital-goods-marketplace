package dgm.order.orders.repository;

import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderFilter;
import dgm.order.orders.domain.OrderSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Чтение заказов модуля «заказы»: схема {@code orders}, роль {@code app_orders}. */
public interface OrderRepository {

    /** Заказы покупателя по условиям, новые выше. Возвращает не больше {@code filter.limit() + 1} строк: лишняя показывает, что страница не последняя. */
    List<Order> findByBuyer(OrderFilter filter);

    /** Заказ покупателя по идентификатору; чужой заказ и несуществующий неотличимы (условие U1). */
    Optional<Order> findOwned(UUID orderId, UUID buyerId);

    /** Заказы «создан», созданные раньше границы: потерян ответ соседа или процесс упал между шагами (сторож, SM-01/T12). Самые старые первыми. */
    List<UUID> findCreatedBefore(Instant border, int limit);

    /** Заказы «ожидает оплаты», резерв которых кончился раньше границы, а событие об этом не пришло (сторож, SM-01/T6). Самые старые первыми. */
    List<UUID> findAwaitingReserveEndedBefore(Instant border, int limit);

    /** Платёжная сессия заказа покупателя; чужой заказ и несуществующий неотличимы (условие U1). */
    Optional<OrderSession> findSession(UUID orderId, UUID buyerId);
}
