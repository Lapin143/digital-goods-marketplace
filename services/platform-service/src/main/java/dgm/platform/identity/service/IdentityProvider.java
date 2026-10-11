package dgm.platform.identity.service;

import dgm.kit.keycloak.KeycloakException;
import dgm.kit.keycloak.KeycloakUser;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Что модулю нужно от поставщика учётных записей (Keycloak, ADR-025). Все вызовы идут по сети и укладываются в 2 секунды; любой может
 * бросить {@link KeycloakException}. Вызывать их внутри транзакции базы нельзя (правило 2 среза): сначала решение и Outbox в базе, потом
 * этот интерфейс, а что не дошло, повторяет {@link IdentityReconciler}. Операции идемпотентны.
 */
public interface IdentityProvider {

    Optional<KeycloakUser> findUser(UUID id);

    /** Страница пользователей: {@code first} записей пропущено, не больше {@code max} возвращено. */
    List<KeycloakUser> listUsers(int first, int max);

    /** Роли платформы, назначенные пользователю в Keycloak; прочие роли realm в ответ не входят. */
    Set<String> platformRoles(UUID id);

    /** Настроен ли второй фактор (учётные данные типа {@code otp}). */
    boolean hasSecondFactor(UUID id);

    /** Пользователь вошёл через внешнего поставщика (VK ID), а не паролем. */
    boolean federated(UUID id);

    /**
     * Оставляет пользователю ровно одну роль платформы. Роль сотрудника без настроенного второго фактора дополняется обязательным
     * действием «настроить TOTP» (INV-37).
     */
    void applyRole(UUID id, String role, boolean requireTotp);

    /** Отключает учётную запись и завершает сессии. Включение обратно платформа не делает никогда (US-8.2). */
    void disable(UUID id);

    /** Удаляет пользователя вместе с сессиями (анонимизация, ADR-014). */
    void delete(UUID id);

    /** Выставляет атрибут {@code phone_verified} для токена (ADR-010). */
    void markPhoneVerified(UUID id);
}
