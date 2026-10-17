package dgm.catalog.sellers.domain;

import java.util.UUID;

/**
 * Модератор, принимающий решение: попадает в события и в запись аудита.
 *
 * @param id идентификатор пользователя
 * @param ip адрес, с которого пришёл запрос (у сотрудника в журнал аудита идёт как есть)
 */
public record Moderator(UUID id, String ip) {

    /** Роль исполнителя в записи аудита: решения по заявкам принимает только модератор (OP-12). */
    public static final String ROLE = "moderator";
}
