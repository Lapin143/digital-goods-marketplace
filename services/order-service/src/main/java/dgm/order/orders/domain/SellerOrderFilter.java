package dgm.order.orders.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Условия выборки заказов продавца (OP-100, US-11.2). Продавец задан всегда: чужие заказы в выборку не попадают ни при каком фильтре (условие U3).
 *
 * @param sellerId    профиль продавца, чьи заказы читаются (из токена или заголовка шлюза при доступе по API-ключу)
 * @param statuses    статусы, подходит любой из них
 * @param createdFrom создан не раньше (включительно)
 * @param createdTo   создан раньше (не включая)
 * @param limit       размер страницы
 * @param after       позиция курсора: последний показанный заказ
 */
public record SellerOrderFilter(UUID sellerId, Optional<List<String>> statuses, Optional<Instant> createdFrom, Optional<Instant> createdTo, int limit,
                                Optional<OrderFilter.After> after) {
}
