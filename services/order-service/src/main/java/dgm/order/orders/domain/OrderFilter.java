package dgm.order.orders.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Условия выборки истории заказов покупателя (US-5.9). Владелец задан всегда: чужие заказы в выборку не попадают (условие U1).
 *
 * @param buyerId     покупатель, чьи заказы читаются ({@code sub} токена)
 * @param statuses    статусы, подходит любой из них
 * @param createdFrom создан не раньше (включительно)
 * @param createdTo   создан раньше (не включая)
 * @param limit       размер страницы
 * @param after       позиция курсора: последний показанный заказ
 */
public record OrderFilter(UUID buyerId, Optional<List<String>> statuses, Optional<Instant> createdFrom, Optional<Instant> createdTo,
                          int limit, Optional<After> after) {

    /** Позиция в порядке «новые выше». */
    public record After(Instant createdAt, UUID id) {
    }
}
