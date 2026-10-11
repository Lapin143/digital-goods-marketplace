package dgm.platform.identity.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.platform.identity.domain.LastAdminException;
import dgm.platform.identity.domain.NewAccount;
import dgm.platform.identity.domain.PhoneTakenException;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.domain.UserFilter;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Учётные записи в PostgreSQL под ролью {@code app_identity}. Список опирается на индексы {@code ix_user_account_registered_at} и
 * {@code ix_user_account_role}, поиск по адресу на уникальный индекс {@code uq_user_account_email}.
 */
@Component
public final class JdbcUserAccountRepository implements UserAccountRepository {

    static final String MODULE = "identity";
    private static final String COLUMNS = "id, email, name, role, has_2fa, status, phone_confirmed, phone_synced_to_idp, anonymized, "
            + "registered_at, deactivated_at, version";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Текст исключения из триггера {@code identity.keep_last_admin}: по нему отличается нарушение INV-38 от остальных проверок. */
    private static final String LAST_ADMIN_MESSAGE = "последнего активного администратора";

    private final JdbcTemplate jdbc;

    public JdbcUserAccountRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public Optional<UserAccount> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from identity.user_account where id = ?", (rs, row) -> map(rs), id).stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findForUpdate(UUID id) {
        return jdbc.query("select " + COLUMNS + " from identity.user_account where id = ? for update", (rs, row) -> map(rs), id).stream()
                .findFirst();
    }

    @Override
    public List<UserAccount> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String marks = String.join(", ", Collections.nCopies(ids.size(), "?"));
        return jdbc.query("select " + COLUMNS + " from identity.user_account where id in (" + marks + ")", (rs, row) -> map(rs), ids.toArray());
    }

    @Override
    public boolean insert(NewAccount account) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        // Без указания цели: конфликт и по идентификатору (два первых запроса), и по адресу (uq_user_account_email) даёт ноль строк
        int rows = jdbc.update("insert into identity.user_account (id, email, name, role, has_2fa, audit_salt, registered_at) "
                + "values (?, ?, ?, ?, ?, ?, ?) on conflict do nothing",
                account.id(), account.email(), account.name(), account.role(), account.has2fa(), salt, utc(account.registeredAt()));
        return rows == 1;
    }

    @Override
    public void updateRole(UUID id, String role) {
        try {
            jdbc.update("update identity.user_account set role = ? where id = ?", role, id);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Override
    public void deactivate(UUID id, Instant at) {
        try {
            jdbc.update("update identity.user_account set status = 'deactivated', deactivated_at = ? where id = ? and status = 'active'",
                    utc(at), id);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Override
    public void anonymize(UUID id) {
        jdbc.update("update identity.user_account set email = ?, name = 'Удалён', phone = null, phone_confirmed = false, "
                + "phone_synced_to_idp = false, previous_email = null, previous_email_until = null, audit_salt = null, anonymized = true "
                + "where id = ? and status = 'deactivated' and not anonymized", "anonymized-" + id + "@invalid", id);
    }

    @Override
    public void updateHas2fa(UUID id, boolean has2fa) {
        jdbc.update("update identity.user_account set has_2fa = ? where id = ? and has_2fa <> ?", has2fa, id, has2fa);
    }

    @Override
    public List<UserAccount> list(UserFilter f) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from identity.user_account where true");
        List<Object> args = new ArrayList<>();
        f.role().ifPresent(role -> {
            sql.append(" and role = ?");
            args.add(role);
        });
        f.status().ifPresent(status -> {
            sql.append(" and status = ?");
            args.add(status);
        });
        f.email().ifPresent(email -> {
            sql.append(" and email = ?");
            args.add(email);
        });
        f.after().ifPresent(after -> {
            sql.append(" and (registered_at, id) < (?, ?)");
            args.add(utc(after.registeredAt()));
            args.add(after.id());
        });
        sql.append(" order by registered_at desc, id desc limit ?");
        args.add(f.limit() + 1);
        return jdbc.query(sql.toString(), (rs, row) -> map(rs), args.toArray());
    }

    @Override
    public Optional<UserAccount> findByPhone(String phone) {
        return jdbc.query("select " + COLUMNS + " from identity.user_account where phone = ?", (rs, row) -> map(rs), phone).stream().findFirst();
    }

    @Override
    public boolean confirmPhone(UUID id, String phone) {
        try {
            return jdbc.update("update identity.user_account set phone = ?, phone_confirmed = true, phone_synced_to_idp = false "
                    + "where id = ? and status = 'active' and not phone_confirmed", phone, id) == 1;
        } catch (DuplicateKeyException e) {
            throw new PhoneTakenException(e);
        }
    }

    @Override
    public Optional<byte[]> auditSalt(UUID id) {
        return jdbc.query("select audit_salt from identity.user_account where id = ? and audit_salt is not null",
                (rs, row) -> rs.getBytes("audit_salt"), id).stream().findFirst();
    }

    @Override
    public List<UUID> unsyncedPhones(int limit) {
        return jdbc.query("select id from identity.user_account where phone_confirmed and not phone_synced_to_idp order by id limit ?",
                (rs, row) -> rs.getObject("id", UUID.class), limit);
    }

    @Override
    public void markPhoneSynced(UUID id) {
        jdbc.update("update identity.user_account set phone_synced_to_idp = true where id = ? and phone_confirmed and not phone_synced_to_idp", id);
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String message = cause.getMessage();
        if (message != null && message.contains(LAST_ADMIN_MESSAGE)) {
            return new LastAdminException(e);
        }
        return e;
    }

    private static UserAccount map(ResultSet rs) throws SQLException {
        return new UserAccount(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("name"),
                rs.getString("role"),
                rs.getBoolean("has_2fa"),
                rs.getString("status"),
                rs.getBoolean("phone_confirmed"),
                rs.getBoolean("phone_synced_to_idp"),
                rs.getBoolean("anonymized"),
                rs.getObject("registered_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(rs.getObject("deactivated_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant),
                rs.getInt("version"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
