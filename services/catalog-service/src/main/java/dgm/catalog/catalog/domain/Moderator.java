package dgm.catalog.catalog.domain;

import java.util.UUID;

/**
 * Модератор, принимающий решение по товару: попадает в запись аудита.
 *
 * @param id идентификатор пользователя
 * @param ip адрес, с которого пришёл запрос (у сотрудника в журнал аудита идёт как есть)
 */
public record Moderator(UUID id, String ip) {

    /** Роль исполнителя в записи аудита: решения по товарам принимает только модератор (OP-22 – OP-24). */
    public static final String ROLE = "moderator";
}
