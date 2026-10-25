package dgm.platform.support.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Условия выборки обращений покупателя (US-7.1). Порядок: новые выше, при равном времени по идентификатору. Чужих обращений выборка
 * не видит: покупатель входит в условие всегда.
 *
 * @param buyerId  покупатель, чьи обращения читаются
 * @param orderId  только обращения этого заказа
 * @param statuses только эти статусы; пустой список: любые
 * @param limit    размер страницы
 * @param after    позиция курсора: последнее показанное обращение
 */
public record TicketFilter(UUID buyerId, Optional<UUID> orderId, List<String> statuses, int limit, Optional<After> after) {

    public TicketFilter {
        statuses = List.copyOf(statuses);
    }

    /** Позиция в порядке «новые выше». */
    public record After(Instant createdAt, UUID id) {
    }
}
