package dgm.platform.identity.testing;

import dgm.platform.identity.domain.EmailTakenException;
import dgm.platform.identity.domain.LastAdminException;
import dgm.platform.identity.domain.NewAccount;
import dgm.platform.identity.domain.PhoneTakenException;
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
    private final Map<UUID, String> phones = new LinkedHashMap<>();
    private final Map<UUID, String> previousEmails = new LinkedHashMap<>();
    private final Map<UUID, Instant> previousUntil = new LinkedHashMap<>();
    private boolean racingEmail;
    private boolean racingPhone;
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
                a.deactivatedAt(), a.version(), a.sellerId()));
    }

    /** Подтверждённый номер у записи, как после успешного подтверждения (для тестов смены e-mail). */
    public UserAccount givePhone(UUID id, String phone) {
        phones.put(id, phone);
        return confirmPhone(id);
    }

    /** Номер у записи (для проверок); пусто, если не привязан. */
    public Optional<String> phoneOf(UUID id) {
        return Optional.ofNullable(phones.get(id));
    }

    /** Следующее подтверждение номера проиграет гонку: уникальный индекс отклонит запись, как если бы соперник успел раньше. */
    public void loseNextPhoneRace() {
        this.racingPhone = true;
    }

    /** Следующая смена адреса проиграет гонку: уникальный индекс отклонит запись, как если бы соперник успел зарегистрировать адрес раньше. */
    public void loseNextEmailRace() {
        this.racingEmail = true;
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
                a.registeredAt(), a.deactivatedAt(), a.version() + 1, role.equals("seller") ? a.sellerId() : Optional.empty()));
    }

    @Override
    public void assignSeller(UUID id, UUID sellerId) {
        if (rows.values().stream().anyMatch(o -> !o.id().equals(id) && o.sellerId().filter(sellerId::equals).isPresent())) {
            throw new org.springframework.dao.DuplicateKeyException("uq_user_account_seller_id");
        }
        UserAccount a = rows.get(id);
        put(new UserAccount(a.id(), a.email(), a.name(), "seller", a.has2fa(), a.status(), a.phoneConfirmed(), a.phoneSynced(), a.anonymized(),
                a.registeredAt(), a.deactivatedAt(), a.version() + 1, Optional.of(sellerId)));
    }

    @Override
    public void deactivate(UUID id, Instant at) {
        UserAccount a = rows.get(id);
        requireAnotherAdmin(a, true);
        put(new UserAccount(a.id(), a.email(), a.name(), a.role(), a.has2fa(), UserAccount.DEACTIVATED, a.phoneConfirmed(), a.phoneSynced(),
                a.anonymized(), a.registeredAt(), Optional.of(at), a.version() + 1, a.sellerId()));
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
        phones.remove(id);
        previousEmails.remove(id);
        previousUntil.remove(id);
        put(new UserAccount(a.id(), "anonymized-" + id + "@invalid", "Удалён", a.role(), a.has2fa(), a.status(), false, false, true,
                a.registeredAt(), a.deactivatedAt(), a.version() + 1, a.sellerId()));
    }

    @Override
    public void updateHas2fa(UUID id, boolean has2fa) {
        UserAccount a = rows.get(id);
        put(new UserAccount(a.id(), a.email(), a.name(), a.role(), has2fa, a.status(), a.phoneConfirmed(), a.phoneSynced(), a.anonymized(),
                a.registeredAt(), a.deactivatedAt(), a.version() + 1, a.sellerId()));
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
    public Optional<String> confirmedPhone(UUID id) {
        UserAccount a = rows.get(id);
        return a == null || !a.active() || a.anonymized() || !a.phoneConfirmed() ? Optional.empty() : Optional.ofNullable(phones.get(id));
    }

    @Override
    public boolean emailExists(String email) {
        return rows.values().stream().anyMatch(a -> a.email().equals(email));
    }

    @Override
    public Optional<UserAccount> findByPhone(String phone) {
        return phones.entrySet().stream().filter(e -> e.getValue().equals(phone)).map(e -> rows.get(e.getKey())).findFirst();
    }

    @Override
    public boolean confirmPhone(UUID id, String phone) {
        if (racingPhone) {
            racingPhone = false;
            throw new PhoneTakenException(null);
        }
        UserAccount a = rows.get(id);
        if (a == null || !a.active() || a.phoneConfirmed()) {
            return false;
        }
        if (phones.entrySet().stream().anyMatch(e -> e.getValue().equals(phone))) {
            throw new PhoneTakenException(null);
        }
        phones.put(id, phone);
        put(new UserAccount(a.id(), a.email(), a.name(), a.role(), a.has2fa(), a.status(), true, false, a.anonymized(), a.registeredAt(),
                a.deactivatedAt(), a.version() + 1, a.sellerId()));
        return true;
    }

    @Override
    public boolean replaceEmail(UUID id, String newEmail, Instant until) {
        if (racingEmail) {
            racingEmail = false;
            throw new EmailTakenException(null);
        }
        UserAccount a = rows.get(id);
        if (a == null || !a.active() || a.anonymized() || a.email().equals(newEmail)) {
            return false;
        }
        if (rows.values().stream().anyMatch(o -> o.email().equals(newEmail))) {
            throw new EmailTakenException(null);
        }
        previousEmails.put(id, a.email());
        previousUntil.put(id, until);
        put(new UserAccount(a.id(), newEmail, a.name(), a.role(), a.has2fa(), a.status(), a.phoneConfirmed(), a.phoneSynced(), a.anonymized(),
                a.registeredAt(), a.deactivatedAt(), a.version() + 1, a.sellerId()));
        return true;
    }

    @Override
    public Optional<String> previousEmail(UUID id, Instant now) {
        UserAccount a = rows.get(id);
        if (a == null || !a.active() || a.anonymized() || !previousEmails.containsKey(id) || !previousUntil.get(id).isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(previousEmails.get(id));
    }

    /** Прежний адрес как он хранится (для проверок), даже если срок вышел; пусто, если смены не было или адрес очищен. */
    public Optional<String> storedPreviousEmail(UUID id) {
        return Optional.ofNullable(previousEmails.get(id));
    }

    @Override
    public int clearExpiredPreviousEmails(Instant now, int limit) {
        List<UUID> expired = previousUntil.entrySet().stream().filter(e -> !e.getValue().isAfter(now)).map(Map.Entry::getKey).limit(limit).toList();
        for (UUID id : expired) {
            previousEmails.remove(id);
            previousUntil.remove(id);
        }
        return expired.size();
    }

    @Override
    public Optional<byte[]> auditSalt(UUID id) {
        UserAccount a = rows.get(id);
        if (a == null || a.anonymized()) {
            return Optional.empty();
        }
        byte[] salt = new byte[16];
        java.util.Arrays.fill(salt, (byte) (id.hashCode() & 0x7f));
        return Optional.of(salt);
    }

    @Override
    public List<UUID> unsyncedPhones(int limit) {
        return rows.values().stream().filter(a -> a.phoneConfirmed() && !a.phoneSynced()).map(UserAccount::id).limit(limit).toList();
    }

    @Override
    public void markPhoneSynced(UUID id) {
        UserAccount a = rows.get(id);
        put(new UserAccount(a.id(), a.email(), a.name(), a.role(), a.has2fa(), a.status(), a.phoneConfirmed(), true, a.anonymized(),
                a.registeredAt(), a.deactivatedAt(), a.version() + 1, a.sellerId()));
    }
}
