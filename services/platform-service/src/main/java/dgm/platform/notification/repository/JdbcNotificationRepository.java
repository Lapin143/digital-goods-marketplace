package dgm.platform.notification.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.kit.json.Json;
import dgm.platform.notification.domain.NewNotification;
import dgm.platform.notification.domain.Notification;
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
 * Очередь уведомлений в PostgreSQL под ролью {@code app_notification}. Выбор очередного письма идёт по индексу
 * {@code ix_notification_dispatch} с {@code FOR UPDATE SKIP LOCKED}, дедупликация по уникальному индексу
 * {@code uq_notification_source_event_id_template_user_id}: одно событие даёт одно письмо по шаблону каждому получателю (Р-153).
 */
@Component
public final class JdbcNotificationRepository implements NotificationRepository {

    static final String MODULE = "notification";
    private static final String COLUMNS = "id, user_id, template, channel, params::text as params, source_event_id, attempts, created_at";

    private final JdbcTemplate jdbc;

    public JdbcNotificationRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public boolean insert(NewNotification notification) {
        int rows = jdbc.update("insert into notification.notification (id, user_id, template, channel, params, source_event_id, "
                + "next_attempt_at, created_at, updated_at) values (?, ?, ?, 'email', ?::jsonb, ?, ?, ?, ?) "
                + "on conflict (source_event_id, template, user_id) where source_event_id is not null do nothing",
                notification.id(), notification.userId(), notification.template(), Json.write(notification.params()), notification.sourceEventId(),
                utc(notification.createdAt()), utc(notification.createdAt()), utc(notification.createdAt()));
        return rows == 1;
    }

    @Override
    public Optional<Notification> claim(Instant now, Instant leaseUntil) {
        return jdbc.query("update notification.notification set next_attempt_at = ?, updated_at = ? where id = ("
                + "select id from notification.notification where status = 'queued' and next_attempt_at <= ? "
                + "order by next_attempt_at, id limit 1 for update skip locked) returning " + COLUMNS,
                (rs, row) -> map(rs), utc(leaseUntil), utc(now), utc(now)).stream().findFirst();
    }

    @Override
    public Optional<Notification> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from notification.notification where id = ?", (rs, row) -> map(rs), id).stream().findFirst();
    }

    @Override
    public boolean markSent(UUID id, int attempts, Instant at) {
        return jdbc.update("update notification.notification set status = 'sent', attempts = ?, sent_at = ?, next_attempt_at = null, "
                + "last_error_code = null, updated_at = ? where id = ? and status = 'queued'", attempts, utc(at), utc(at), id) == 1;
    }

    @Override
    public boolean reschedule(UUID id, int attempts, Instant nextAttemptAt, String errorCode, Instant at) {
        return jdbc.update("update notification.notification set attempts = ?, next_attempt_at = ?, last_error_code = ?, updated_at = ? "
                + "where id = ? and status = 'queued'", attempts, utc(nextAttemptAt), errorCode, utc(at), id) == 1;
    }

    @Override
    public boolean markFailed(UUID id, int attempts, String errorCode, boolean deadQueue, Instant at) {
        return jdbc.update("update notification.notification set status = 'failed', attempts = ?, last_error_code = ?, in_dead_queue = ?, "
                + "next_attempt_at = null, updated_at = ? where id = ? and status = 'queued'", attempts, errorCode, deadQueue, utc(at), id) == 1;
    }

    private static Notification map(ResultSet rs) throws SQLException {
        UUID source = rs.getObject("source_event_id", UUID.class);
        return new Notification(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("template"),
                rs.getString("channel"), Json.parseObject(rs.getString("params")), Optional.ofNullable(source), rs.getInt("attempts"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
