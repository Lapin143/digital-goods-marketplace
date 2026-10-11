package dgm.platform.identity.domain;

import java.util.UUID;

/**
 * Сотрудник, выполняющий действие: попадает в события и запись аудита.
 *
 * @param id   идентификатор пользователя
 * @param role роль в момент действия
 * @param ip   адрес, с которого пришёл запрос (у сотрудника в аудит идёт как есть)
 */
public record Actor(UUID id, String role, String ip) {
}
