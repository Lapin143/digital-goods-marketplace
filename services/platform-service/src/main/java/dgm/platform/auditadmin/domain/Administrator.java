package dgm.platform.auditadmin.domain;

import java.util.UUID;

/**
 * Администратор, меняющий параметр: попадает в запись аудита и в событие {@code config.changed}.
 *
 * @param ip адрес, с которого пришёл запрос (у сотрудника в журнал аудита идёт как есть); пустая строка, если адреса нет
 */
public record Administrator(UUID id, String ip) {

    /** Роль исполнителя в записи аудита: параметры меняет только администратор (OP-83). */
    public static final String ROLE = "admin";
}
