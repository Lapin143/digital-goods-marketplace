package dgm.platform.identity.testing;

import dgm.platform.identity.domain.LastAdminException;
import dgm.platform.identity.domain.NewAccount;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.domain.UserFilter;
import dgm.platform.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Учётные записи в памяти с теми же правилами, что у таблицы: уникальный адрес, последний администратор, анонимизация. */
public final class InMemoryAccounts implements UserAccountRepository {

    private final Map<UUID, UserAccount> rows = new LinkedHashMap<>();
    private Runnable beforeInsert = () -> { };

    /** Действие перед вставкой: имитирует соперника, который успел создать запись раньше. */
    public void beforeInsert(Runnable action) {
        this.beforeInsert = action;
    }

    public UserAccount put(UserAccount account) {
        rows.put(account.id(), account);
        return account;
    }

    public UserAccount add(UUID id, String email, String role, Instant registeredAt) {
        return put(new UserAccount(id, email, "Имя " + id.toString().substring(0, 4), role, false, UserAccount.ACTIVE, false, false, false,
                registeredAt, Optional.empty(), 1));
    }

    public UserAccount confirmPhone(UUID id) {
        UserAccount a = rows.get(id);
        return put(new UserAccount(a.id(), a.email(), a.name(), a.role(), a.has2fa(), a.status(), true, false, a.anonymized(), a.registeredAt(),
                a.deactivatedAt(), a.version()));
    }

    public int size() {
        return rows.size();
    }

    @Override
    public Optional<UserAccount> find(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<UserAccount> findForUpdate(UUID id) {
        return find(id);
    }

    @Override
    public List<UserAccount> findAll(Collection<UUID> ids) {
        List<UserAccount> found = new ArrayList<>();
        for (UUID id : ids) {
            if (rows.containsKey(id)) {
                found.add(rows.get(id));
            }
        }
        return found;
    }

    @Override
    public boolean insert(NewAccount account) {
        beforeInsert.run();
        beforeInsert = () -> { };
        boolean emailTaken = rows.values().stream().anyMatch(a -> a.email().equals(account.email()));
        if (rows.containsKey(account.id()) || emailTaken) {
            return false;
        }
        put(new UserAccount(account.id(), account.email(), account.name(), account.role(), account.has2fa(), UserAccount.ACTIVE, false, false,
                false, account.registeredAt(), Optional.empty(), 1));
        return true;
    }

    @Override
    public void updateRole(UUID id, String role) {
        UserAccount a = rows.get(id);
        requireAnotherAdmin(a, !role.equals("admin"));
        put(new UserAccount(a.id(), a.email(), a.name(), role, a.has2fa(), a.status(), a.phoneConfirmed(), a.phoneSynced(), a.anonymized(),
                a.registeredAt(), a.deactivatedAt(), a.version() + 1));
    }

    @Override
    public void deactivate(UUID id, Instant at) {
        UserAccount a = rows.get(id);
        requireAnotherAdmin(a, true);
        put(new UserAccount(a.id(), a.email(), a.name(), a.role(), a.has2fa(), UserAccount.DEACTIVATED, a.phoneConfirmed(), a.phoneSynced(),
                a.anonymized(), a.registeredAt(), Optional.of(at), a.version() + 1));
    }

    /** INV-38: активного администратора нельзя убрать, если других активных нет. */
    private void requireAnotherAdmin(UserAccount a, boolean leavesAdminRole) {
        if (a.role().equals("admin") && a.active() && leavesAdminRole
                && rows.values().stream().noneMatch(o -> !o.id().equals(a.id()) && o.role().equals("admin") && o.active())) {
            throw new LastAdminException(null);
        }
    }

    @Override
    public void anonymize(UUID id) {
        UserAccount a = rows.get(id);
        put(new UserAccount(a.id(), "anonymized-" + id + "@invalid", "Удалён", a.role(), a.has2fa(), a.status(), false, false, true,
                a.registeredAt(), a.deactivatedAt(), a.version() + 1));
    }

    @Override
    public void updateHas2fa(UUID id, boolean has2fa) {
        UserAccount a = rows.get(id);
        put(new UserAccount(a.id(), a.email(), a.name(), a.role(), has2fa, a.status(), a.phoneConfirmed(), a.phoneSynced(), a.anonymized(),
                a.registeredAt(), a.deactivatedAt(), a.version() + 1));
    }

    @Override
    public List<UserAccount> list(UserFilter f) {
        Comparator<UserAccount> newestFirst = Comparator.comparing(UserAccount::registeredAt).thenComparing(UserAccount::id).reversed();
        return rows.values().stream()
                .filter(a -> f.role().map(a.role()::equals).orElse(true))
                .filter(a -> f.status().map(a.status()::equals).orElse(true))
                .filter(a -> f.email().map(a.email()::equals).orElse(true))
                .filter(a -> f.after().map(after -> a.registeredAt().isBefore(after.registeredAt())
                        || (a.registeredAt().equals(after.registeredAt()) && a.id().compareTo(after.id()) < 0)).orElse(true))
                .sorted(newestFirst)
                .limit(f.limit() + 1L)
                .toList();
    }

    @Override
    public List<UUID> unsyncedPhones(int limit) {
        return rows.values().stream().filter(a -> a.phoneConfirmed() && !a.phoneSynced()).map(UserAccount::id).limit(limit).toList();
    }

    @Override
    public void markPhoneSynced(UUID id) {
        UserAccount a = rows.get(id);
        put(new UserAccount(a.id(), a.email(), a.name(), a.role(), a.has2fa(), a.status(), a.phoneConfirmed(), true, a.anonymized(),
                a.registeredAt(), a.deactivatedAt(), a.version() + 1));
    }
}
