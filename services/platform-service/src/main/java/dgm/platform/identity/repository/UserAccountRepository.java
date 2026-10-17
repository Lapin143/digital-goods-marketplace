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

    /**
     * Меняет роль. Профиль продавца ({@code seller_id}) остаётся только у роли {@code seller}, при любой другой роли он снимается.
     *
     * @throws dgm.platform.identity.domain.LastAdminException снятие роли у последнего активного администратора (INV-38)
     */
    void updateRole(UUID id, String role);

    /**
     * Назначает роль продавца вместе с идентификатором его профиля (INV-36, Р-18): единственный путь к роли {@code seller}.
     *
     * @throws org.springframework.dao.DuplicateKeyException профиль уже привязан к другой учётной записи
     */
    void assignSeller(UUID id, UUID sellerId);

    /** @throws dgm.platform.identity.domain.LastAdminException деактивация последнего активного администратора (INV-38) */
    void deactivate(UUID id, Instant at);

    /** Заменяет персональные значения служебными, удаляет соль аудита (INV-44). Запись должна быть деактивирована. */
    void anonymize(UUID id);

    void updateHas2fa(UUID id, boolean has2fa);

    /** До {@code limit + 1} записей: лишняя говорит, что есть следующая страница. */
    List<UserAccount> list(UserFilter filter);

    /** Запись, к которой привязан номер (E.164); пусто, если номер не привязан. Номера есть только у подтверждённых записей. */
    Optional<UserAccount> findByPhone(String phone);

    /**
     * Записывает подтверждённый номер и признак подтверждения; признак передачи в Keycloak сбрасывается, чтобы сверка довела атрибут
     * {@code phone_verified} до токена.
     *
     * @return {@code false}: запись не активна или телефон у неё уже подтверждён, ничего не изменено
     * @throws dgm.platform.identity.domain.PhoneTakenException номер уже у другой записи (гонка двух подтверждений, INV-34)
     */
    boolean confirmPhone(UUID id, String phone);

    /** Соль пользователя для хешей аудита (ADR-014); пусто у анонимизированной записи. */
    Optional<byte[]> auditSalt(UUID id);

    /** Подтверждённые телефоны, ещё не переданные в Keycloak. */
    List<UUID> unsyncedPhones(int limit);

    void markPhoneSynced(UUID id);
}
