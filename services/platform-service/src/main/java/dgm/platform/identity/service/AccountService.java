package dgm.platform.identity.service;

import dgm.kit.keycloak.KeycloakException;
import dgm.kit.keycloak.KeycloakUser;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.CurrentUser;
import dgm.platform.identity.domain.AccountNames;
import dgm.platform.identity.domain.NewAccount;
import dgm.platform.identity.domain.Roles;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.repository.UserAccountRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Создание учётной записи платформы (компонент {@code user-service}, ADR-025): при первом обращении пользователя с токеном и при сверке
 * с Keycloak ({@link IdentityReconciler}). Запись, событие {@code user.registered} в Outbox и признак «создана» живут в одной транзакции;
 * вызовы Keycloak идут до неё и после, но не внутри.
 *
 * <p>Токен доступа персональных данных не содержит (решение Р-30), поэтому адрес и имя читаются из Keycloak Admin REST. Одновременные первые
 * запросы одного пользователя разрешает {@code on conflict do nothing}: проигравший перечитывает запись и событие не пишет.
 */
@Component
public final class AccountService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);
    /** Предел памяти на идентификаторы, для которых запись уже есть: записи не удаляются, поэтому этому знанию можно верить. */
    private static final int KNOWN_LIMIT = 10_000;
    private static final int RETRY_AFTER_SECONDS = 5;

    private final UserAccountRepository accounts;
    private final IdentityProvider provider;
    private final IdentityTransaction tx;
    private final Clock clock;
    private final Set<UUID> known = ConcurrentHashMap.newKeySet();

    public AccountService(UserAccountRepository accounts, IdentityProvider provider, IdentityTransaction tx, Clock clock) {
        this.accounts = accounts;
        this.provider = provider;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Гарантирует, что запись пользователя токена есть (создание при первом обращении, Р-6). Роль и второй фактор для новой записи берутся
     * из токена, адрес и имя из Keycloak.
     *
     * @throws ProblemException 401, если Keycloak не знает пользователя; 503 {@code dependency-unavailable}, если Keycloak не ответил
     */
    public void ensureAccount(AuthenticatedUser user) {
        UUID id = CurrentUser.id(user);
        if (known.contains(id)) {
            return;
        }
        if (accounts.find(id).isPresent()) {
            remember(id);
            return;
        }
        KeycloakUser profile;
        boolean federated;
        try {
            profile = provider.findUser(id).orElseThrow(() -> new ProblemException(ProblemType.UNAUTHENTICATED,
                    "Пользователь токена не найден."));
            federated = provider.federated(id);
        } catch (KeycloakException e) {
            LOG.warn("Учётная запись {} не создана: Keycloak недоступен ({})", id, e.getMessage());
            throw ProblemException.dependencyUnavailable("Учётная запись создаётся, повторите запрос через несколько секунд.",
                    RETRY_AFTER_SECONDS);
        }
        provision(profile, Roles.best(user.roles()), user.secondFactorPassed(), federated);
        remember(id);
    }

    /**
     * Создаёт запись по профилю Keycloak, если её нет, и возвращает сохранённую. Проигравший гонку или повторный вызов получает уже созданную
     * запись без нового события.
     *
     * @throws ProblemException 409 {@code email-not-allowed}: адрес уже у другой записи
     */
    public UserAccount provision(KeycloakUser profile, String role, boolean has2fa, boolean federated) {
        UUID id = UUID.fromString(profile.id());
        NewAccount account = new NewAccount(id, AccountNames.email(profile.email(), profile.username(), id),
                AccountNames.displayName(profile.fullName(), profile.username(), profile.email()), role, has2fa, clock.instant());
        Boolean created = tx.execute(() -> {
            if (!accounts.insert(account)) {
                return Boolean.FALSE;
            }
            tx.publish(IdentityEvents.USER_TOPIC, IdentityEvents.USER_AGGREGATE, id.toString(), IdentityEvents.REGISTERED, 1,
                    IdentityEvents.registered(id, role, federated ? "vk_id" : "password"));
            return Boolean.TRUE;
        });
        Optional<UserAccount> stored = accounts.find(id);
        if (stored.isEmpty()) {
            // Вставка отказала, а записи с этим идентификатором нет: адрес занят другой записью (uq_user_account_email)
            throw new ProblemException(ProblemType.EMAIL_NOT_ALLOWED, "Адрес пользователя уже связан с другой учётной записью.");
        }
        if (Boolean.TRUE.equals(created)) {
            LOG.info("Создана учётная запись {} (роль {})", id, role);
        }
        return stored.get();
    }

    private void remember(UUID id) {
        if (known.size() >= KNOWN_LIMIT) {
            known.clear();
        }
        known.add(id);
    }
}
