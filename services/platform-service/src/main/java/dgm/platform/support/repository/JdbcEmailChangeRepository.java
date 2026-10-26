package dgm.platform.support.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.platform.support.domain.EmailChange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Этапы смены e-mail в PostgreSQL под ролью {@code app_support}. Переходы это условные {@code UPDATE}, порядок проверок и очистку адреса
 * дополнительно держат ограничения таблицы ({@code ck_email_change_order_of_checks}, {@code ck_email_change_pii_cleanup}).
 */
@Component
public final class JdbcEmailChangeRepository implements EmailChangeRepository {

    private static final String COLUMNS = "ticket_id, user_id, new_email, new_email_masked, phone_check, new_email_check, status, started_at, updated_at";
    /** Смена, оба подтверждения которой получены: её заменой не выбросить. */
    private static final String CONFIRMED = "(support.email_change.status = 'awaiting_email_code' and support.email_change.new_email_check = 'passed')";

    private final JdbcTemplate jdbc;

    public JdbcEmailChangeRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(JdbcSupportRepository.MODULE);
    }

    @Override
    public Optional<EmailChange> find(UUID ticketId) {
        return jdbc.query("select " + COLUMNS + " from support.email_change where ticket_id = ?", (rs, row) -> map(rs), ticketId).stream().findFirst();
    }

    @Override
    public boolean start(UUID ticketId, UUID userId, String newEmail, String newEmailMasked, Instant at) {
        return jdbc.update("insert into support.email_change (ticket_id, user_id, new_email, new_email_masked, phone_check, new_email_check, status, "
                + "started_at, updated_at) values (?, ?, ?, ?, 'pending', 'not_started', 'awaiting_phone_code', ?, ?) "
                + "on conflict (ticket_id) do update set user_id = excluded.user_id, new_email = excluded.new_email, "
                + "new_email_masked = excluded.new_email_masked, phone_check = 'pending', new_email_check = 'not_started', "
                + "status = 'awaiting_phone_code', started_at = excluded.started_at, updated_at = excluded.updated_at "
                + "where support.email_change.status <> 'completed' and not " + CONFIRMED,
                ticketId, userId, newEmail, newEmailMasked, utc(at), utc(at)) == 1;
    }

    @Override
    public boolean phonePassed(UUID ticketId, Instant at) {
        return jdbc.update("update support.email_change set phone_check = 'passed', new_email_check = 'pending', status = 'awaiting_email_code', "
                + "updated_at = ? where ticket_id = ? and status = 'awaiting_phone_code'", utc(at), ticketId) == 1;
    }

    @Override
    public boolean newEmailPassed(UUID ticketId, Instant at) {
        return jdbc.update("update support.email_change set new_email_check = 'passed', updated_at = ? "
                + "where ticket_id = ? and status = 'awaiting_email_code' and new_email_check = 'pending'", utc(at), ticketId) == 1;
    }

    @Override
    public boolean fail(UUID ticketId, String phoneCheck, String newEmailCheck, Instant at) {
        return jdbc.update("update support.email_change set status = 'failed', phone_check = ?, new_email_check = ?, new_email = null, updated_at = ? "
                + "where ticket_id = ? and status in ('awaiting_phone_code', 'awaiting_email_code') and new_email_check <> 'passed'",
                phoneCheck, newEmailCheck, utc(at), ticketId) == 1;
    }

    private static EmailChange map(ResultSet rs) throws SQLException {
        return new EmailChange(rs.getObject("ticket_id", UUID.class), rs.getObject("user_id", UUID.class), Optional.ofNullable(rs.getString("new_email")),
                rs.getString("new_email_masked"), rs.getString("phone_check"), rs.getString("new_email_check"), rs.getString("status"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
