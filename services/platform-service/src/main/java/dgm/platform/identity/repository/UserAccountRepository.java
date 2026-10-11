package dgm.platform.identity.repository;

import dgm.platform.identity.domain.NewAccount;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.domain.UserFilter;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Учётные записи платформы (схема {@code identity}). Методы изменения вызываются внутри транзакции модуля. */
public interface UserAccountRepository {

    Optional<UserAccount> find(UUID id);

    /** Запись под блокировкой строки до конца транзакции: два администратора не изменят одну учётную запись одновременно. */
    Optional<UserAccount> findForUpdate(UUID id);

    List<UserAccount> findAll(Collection<UUID> ids);

    /**
     * Создаёт запись, если её нет.
     *
     * @return {@code false}: запись не создана, потому что идентификатор или адрес уже заняты (одновременное создание или адрес другой записи)
     */
    boolean insert(NewAccount account);

    /** @throws dgm.platform.identity.domain.LastAdminException снятие роли у последнего активного администратора (INV-38) */
    void updateRole(UUID id, String role);

    /** @throws dgm.platform.identity.domain.LastAdminException деактивация последнего активного администратора (INV-38) */
    void deactivate(UUID id, Instant at);

    /** Заменяет персональные значения служебными, удаляет соль аудита (INV-44). Запись должна быть деактивирована. */
    void anonymize(UUID id);

    void updateHas2fa(UUID id, boolean has2fa);

    /** До {@code limit + 1} записей: лишняя говорит, что есть следующая страница. */
    List<UserAccount> list(UserFilter filter);

    /** Подтверждённые телефоны, ещё не переданные в Keycloak. */
    List<UUID> unsyncedPhones(int limit);

    void markPhoneSynced(UUID id);
}
