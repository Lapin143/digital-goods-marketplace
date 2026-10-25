package dgm.platform.auditadmin.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Условия выборки журнала аудита для администратора (US-8.7). Порядок: новые выше, при равном времени по идентификатору записи.
 *
 * @param actorId       исполнитель
 * @param objectType    тип объекта
 * @param objectId      идентификатор объекта
 * @param action        действие
 * @param occurredFrom  не раньше (включительно)
 * @param occurredTo    раньше (не включая)
 * @param limit         размер страницы
 * @param after         позиция курсора: последняя показанная запись
 */
public record AuditFilter(Optional<UUID> actorId, Optional<String> objectType, Optional<String> objectId, Optional<String> action,
                          Optional<Instant> occurredFrom, Optional<Instant> occurredTo, int limit, Optional<After> after) {

    /** Позиция в порядке «новые выше». */
    public record After(Instant occurredAt, UUID id) {
    }
}
