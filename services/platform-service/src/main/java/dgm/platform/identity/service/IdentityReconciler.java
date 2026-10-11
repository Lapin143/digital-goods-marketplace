package dgm.platform.identity.service;

import dgm.kit.keycloak.KeycloakException;
import dgm.kit.keycloak.KeycloakUser;
import dgm.kit.outbox.OutboxRelay;
import dgm.platform.identity.domain.Roles;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.repository.UserAccountRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Сверка с Keycloak (компонент {@code identity-reconciler}, ADR-025, пункт 3): раз в минуту приводит стороны к согласию и этим заменяет надёжную
 * доставку изменений в Keycloak, которой у вызовов Admin REST нет.
 *
 * <p>За один проход читаются все пользователи Keycloak страницами по {@value #PAGE_SIZE}. Для каждого:
 * <ul>
 *   <li>записи в платформе нет: создаётся (роль и второй фактор берутся из Keycloak), в том числе для сотрудников, заведённых в консоли;</li>
 *   <li>запись анонимизирована, а пользователь в Keycloak остался: пользователь удаляется;</li>
 *   <li>запись деактивирована, а пользователь включён: он отключается, сессии завершаются. Обратного пути нет, включённым платформа деактивированного
 *       не делает никогда;</li>
 *   <li>роль в Keycloak отличается от записи: Keycloak приводится к записи (образец платформа, пункт 6 ADR-025);</li>
 *   <li>признак второго фактора отличается от наличия учётных данных {@code otp}: обновляется запись платформы (образец Keycloak).</li>
 * </ul>
 * Затем повторяется передача {@code phone_verified} для подтверждённых телефонов, не переданных в Keycloak. Ошибка по одному пользователю проход
 * не прерывает; недоступность Keycloak прерывает, и следующий проход повторит всё. Проход может идти одновременно у двух экземпляров только в
 * миг смены лидера: все действия идемпотентны.
 *
 * <p>Лидер тот же, что у публикатора Outbox ({@link OutboxRelay#isLeader()}): отдельное соединение с базой для блокировки пул роли
 * {@code app_identity} (предел два соединения) не даёт.
 */
@Component
public final class IdentityReconciler {

    static final int PAGE_SIZE = 100;
    private static final int PHONE_BATCH = 100;
    /** Предохранитель от бесконечного чтения, если Keycloak вернёт одну и ту же страницу: 100 000 пользователей. */
    private static final int MAX_PAGES = 1000;
    private static final Logger LOG = LoggerFactory.getLogger(IdentityReconciler.class);

    /** Итог одного прохода. */
    public record Summary(int checked, int created, int rolesFixed, int disabled, int deleted, int secondFactorUpdated, int phonesSynced,
                          int failed) {
    }

    private final UserAccountRepository accounts;
    private final IdentityProvider provider;
    private final AccountService accountService;
    private final ObjectProvider<OutboxRelay> relay;

    public IdentityReconciler(UserAccountRepository accounts, IdentityProvider provider, AccountService accountService,
                              ObjectProvider<OutboxRelay> relay) {
        this.accounts = accounts;
        this.provider = provider;
        this.accountService = accountService;
        this.relay = relay;
    }

    /** Вход по расписанию: проход делает только лидер. Интервал и первая пауза задаются ключами {@code dgm.identity.*} (application.yml). */
    @Scheduled(fixedDelayString = "${dgm.identity.reconcile-interval:PT1M}", initialDelayString = "${dgm.identity.reconcile-initial-delay:PT20S}")
    void scheduled() {
        OutboxRelay publisher = relay.getIfAvailable();
        if (publisher != null && !publisher.isLeader()) {
            return;
        }
        try {
            Summary summary = runPass();
            if (summary.created() + summary.rolesFixed() + summary.disabled() + summary.deleted() + summary.secondFactorUpdated()
                    + summary.phonesSynced() + summary.failed() > 0) {
                LOG.info("Сверка с Keycloak: проверено {}, создано {}, ролей исправлено {}, отключено {}, удалено {}, обновлено признаков 2FA {}, "
                                + "телефонов передано {}, ошибок {}", summary.checked(), summary.created(), summary.rolesFixed(), summary.disabled(),
                        summary.deleted(), summary.secondFactorUpdated(), summary.phonesSynced(), summary.failed());
            }
        } catch (KeycloakException e) {
            LOG.warn("Сверка с Keycloak прервана, повторится в следующий раз: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Сверка с Keycloak завершилась ошибкой", e);
        }
    }

    /**
     * Один проход без проверки лидерства (для тестов и ручного запуска).
     *
     * @throws KeycloakException Keycloak недоступен: список пользователей не прочитан
     */
    public Summary runPass() {
        Counters counters = new Counters();
        int first = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<KeycloakUser> users = provider.listUsers(first, PAGE_SIZE);
            Set<UUID> ids = new HashSet<>();
            for (KeycloakUser user : users) {
                ids.add(UUID.fromString(user.id()));
            }
            Map<UUID, UserAccount> existing = new HashMap<>();
            for (UserAccount account : accounts.findAll(ids)) {
                existing.put(account.id(), account);
            }
            for (KeycloakUser user : users) {
                counters.checked++;
                UUID id = UUID.fromString(user.id());
                try {
                    reconcile(user, existing.get(id), counters);
                } catch (KeycloakException e) {
                    counters.failed++;
                    LOG.warn("Сверка не закончила пользователя {}: {}", id, e.getMessage());
                } catch (RuntimeException e) {
                    // Сообщение базы может содержать значения полей (адрес), поэтому в журнал идёт только класс ошибки
                    counters.failed++;
                    LOG.warn("Сверка не закончила пользователя {}: {}", id, e.getClass().getSimpleName());
                }
            }
            if (users.size() < PAGE_SIZE) {
                break;
            }
            first += users.size();
        }
        syncPhones(counters);
        return counters.summary();
    }

    private void reconcile(KeycloakUser user, UserAccount account, Counters counters) {
        UUID id = UUID.fromString(user.id());
        if (account == null) {
            String role = Roles.best(provider.platformRoles(id));
            accountService.provision(user, role, provider.hasSecondFactor(id), provider.federated(id));
            counters.created++;
            return;
        }
        if (account.anonymized()) {
            provider.delete(id);
            counters.deleted++;
            return;
        }
        if (!account.active() && user.enabled()) {
            provider.disable(id);
            counters.disabled++;
        }
        if (!provider.platformRoles(id).equals(Set.of(account.role()))) {
            provider.applyRole(id, account.role(), Roles.staff(account.role()) && !account.has2fa());
            counters.rolesFixed++;
        }
        boolean otp = provider.hasSecondFactor(id);
        if (otp != account.has2fa()) {
            accounts.updateHas2fa(id, otp);
            counters.secondFactorUpdated++;
        }
    }

    private void syncPhones(Counters counters) {
        for (UUID id : accounts.unsyncedPhones(PHONE_BATCH)) {
            try {
                provider.markPhoneVerified(id);
                accounts.markPhoneSynced(id);
                counters.phonesSynced++;
            } catch (KeycloakException e) {
                counters.failed++;
                LOG.warn("Признак телефона {} не передан в Keycloak: {}", id, e.getMessage());
            }
        }
    }

    /** Счётчики одного прохода; проход идёт в одном потоке. */
    private static final class Counters {
        private int checked;
        private int created;
        private int rolesFixed;
        private int disabled;
        private int deleted;
        private int secondFactorUpdated;
        private int phonesSynced;
        private int failed;

        Summary summary() {
            return new Summary(checked, created, rolesFixed, disabled, deleted, secondFactorUpdated, phonesSynced, failed);
        }
    }
}
