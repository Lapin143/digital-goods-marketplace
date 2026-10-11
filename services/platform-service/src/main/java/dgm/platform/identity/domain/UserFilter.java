package dgm.platform.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Условия выборки учётных записей для администратора (US-8.1). Порядок: новые выше.
 *
 * @param role   роль
 * @param status статус
 * @param email  точный адрес в нижнем регистре
 * @param limit  размер страницы
 * @param after  позиция курсора: последняя показанная запись
 */
public record UserFilter(Optional<String> role, Optional<String> status, Optional<String> email, int limit, Optional<After> after) {

    /** Позиция в порядке «новые выше». */
    public record After(Instant registeredAt, UUID id) {
    }
}
