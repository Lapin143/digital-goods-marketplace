package dgm.platform.support.domain;

import java.util.UUID;

/**
 * Оператор поддержки, выполняющий действие над обращением: попадает в запись аудита.
 *
 * @param id идентификатор пользователя из токена
 * @param ip адрес, с которого пришёл запрос (у сотрудника в журнал аудита идёт как есть); пустая строка, если адреса нет
 */
public record Operator(UUID id, String ip) {

    /** Роль исполнителя в записи аудита (матрица прав OP-71, OP-72). */
    public static final String ROLE = "support-operator";
}
