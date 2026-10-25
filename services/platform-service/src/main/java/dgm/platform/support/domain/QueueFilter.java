package dgm.platform.support.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Условия выборки очереди оператора (US-7.2). Порядок «старые выше новых»: по времени создания, при равном времени по идентификатору.
 *
 * @param statuses    только эти статусы; пустой список: любые
 * @param operatorId  только обращения этого оператора
 * @param orderNumber только обращения заказа с этим коротким номером; неизвестный номер даёт пустую страницу
 * @param limit       размер страницы
 * @param after       позиция курсора: последнее показанное обращение
 */
public record QueueFilter(List<String> statuses, Optional<UUID> operatorId, OptionalLong orderNumber, int limit, Optional<After> after) {

    public QueueFilter {
        statuses = List.copyOf(statuses);
    }

    /** Позиция в порядке «старые выше». */
    public record After(Instant createdAt, UUID id) {
    }
}
