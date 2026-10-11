package dgm.kit.keycloak;

import java.util.List;
import java.util.Map;

/**
 * Пользователь Keycloak в объёме, нужном платформе.
 *
 * @param id               идентификатор (UUID), он же {@code sub} токена
 * @param username         имя пользователя
 * @param email            адрес электронной почты; {@code null}, если не задан
 * @param firstName        имя из профиля; {@code null}, если не задано
 * @param lastName         фамилия из профиля; {@code null}, если не задана
 * @param enabled          учётная запись включена
 * @param emailVerified    адрес подтверждён
 * @param createdTimestamp время создания, миллисекунды Unix
 * @param attributes       атрибуты профиля (значения многозначные)
 */
public record KeycloakUser(String id, String username, String email, String firstName, String lastName, boolean enabled,
                           boolean emailVerified, long createdTimestamp, Map<String, List<String>> attributes) {

    public KeycloakUser {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Первое значение атрибута или {@code null}. */
    public String attribute(String name) {
        List<String> values = attributes.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** Имя и фамилия через пробел; {@code null}, если не задано ни то, ни другое. */
    public String fullName() {
        String first = firstName == null ? "" : firstName.strip();
        String last = lastName == null ? "" : lastName.strip();
        String joined = (first + " " + last).strip();
        return joined.isEmpty() ? null : joined;
    }

    /** Без адреса и имени: запись попадает в журнал только в таком виде. */
    @Override
    public String toString() {
        return "KeycloakUser[" + id + "]";
    }
}
