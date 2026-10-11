package dgm.platform.identity.api;

import java.util.UUID;

/**
 * Куда и кому писать: адрес и отображаемое имя получателя. Значения живут только в памяти на время отправки, в таблицы других модулей и
 * в журнал они не попадают (INV-44).
 *
 * @param userId идентификатор пользователя
 * @param email  адрес в нормализованном виде
 * @param name   отображаемое имя для обращения в письме
 */
public record Contact(UUID userId, String email, String name) {

    /** Только идентификатор: адрес и имя в журнал не попадают. */
    @Override
    public String toString() {
        return "Contact[" + userId + "]";
    }
}
