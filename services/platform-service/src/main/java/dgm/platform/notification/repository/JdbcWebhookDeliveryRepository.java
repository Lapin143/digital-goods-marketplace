package dgm.platform.notification.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.kit.jdbc.RowClaim;
import dgm.kit.json.Json;
import dgm.platform.notification.domain.NewWebhookDelivery;
import dgm.platform.notification.domain.PendingWebhook;
import dgm.platform.notification.domain.WebhookDelivery;
import dgm.platform.notification.domain.WebhookDeliveryFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Очередь webhook продавцам в PostgreSQL под ролью {@code app_notification}. Выбор очередного уведомления идёт по индексу
 * {@code ix_webhook_delivery_dispatch}, список продавца по {@code ix_webhook_delivery_seller_id}. Дедупликация по уникальному {@code event_id}.
 */
@Component
public final class JdbcWebhookDeliveryRepository implements WebhookDeliveryRepository {

    static final String MODULE = "notification";

    /** Очередь отправки: «ждёт» с наступившим сроком; аренда сдвигает {@code next_attempt_at}. Тело берётся текстом: повтор шлёт те же байты. */
    private static final RowClaim DUE = RowClaim.on("notification.webhook_delivery")
            .where("status = 'queued'")
            .dueColumn("next_attempt_at")
            .orderBy("next_attempt_at, id")
            .returning("id, seller_id, event_id, event_type, payload::text as payload, attempts")
            .build();

    private static final RowMapper<PendingWebhook> CLAIMED = (rs, row) -> new PendingWebhook(rs.getObject("id", UUID.class),
            rs.getObject("seller_id", UUID.class), rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
            rs.getInt("attempts"));

    private static final RowMapper<WebhookDelivery> LISTED = (rs, row) -> new WebhookDelivery(rs.getObject("id", UUID.class),
            rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getLong("order_number"), rs.getString("status"), rs.getInt("attempts"),
            integer(rs, "last_response_code"), instant(rs, "last_attempt_at"), instant(rs, "next_attempt_at"), instant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public JdbcWebhookDeliveryRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public boolean insert(NewWebhookDelivery delivery) {
        return jdbc.update("insert into notification.webhook_delivery (id, seller_id, event_id, event_type, order_number, payload, status, attempts, "
                + "next_attempt_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?::jsonb, 'queued', 0, ?, ?, ?) on conflict (event_id) do nothing",
                delivery.id(), delivery.sellerId(), delivery.eventId(), delivery.eventType(), delivery.orderNumber(), Json.write(delivery.payload()),
                time(delivery.createdAt()), time(delivery.createdAt()), time(delivery.createdAt())) == 1;
    }

    @Override
    public List<PendingWebhook> claimDue(Instant now, Duration lease, int limit) {
        return DUE.claim(jdbc, now, lease, limit, CLAIMED);
    }

    @Override
    public boolean markSent(UUID id, int attempts, int responseCode, Instant at) {
        return jdbc.update("update notification.webhook_delivery set status = 'sent', attempts = ?, last_attempt_at = ?, last_response_code = ?, "
                + "last_error_code = null, sent_at = ?, next_attempt_at = null where id = ? and status = 'queued'",
                attempts, time(at), responseCode, time(at), id) == 1;
    }

    @Override
    public boolean reschedule(UUID id, int attempts, Instant nextAttemptAt, Integer responseCode, String errorCode, Instant at) {
        return jdbc.update("update notification.webhook_delivery set attempts = ?, next_attempt_at = ?, last_attempt_at = ?, last_response_code = ?, "
                + "last_error_code = ? where id = ? and status = 'queued'", attempts, time(nextAttemptAt), time(at), responseCode, errorCode, id) == 1;
    }

    @Override
    public boolean markFailed(UUID id, int attempts, Integer responseCode, String errorCode, Instant at) {
        return jdbc.update("update notification.webhook_delivery set status = 'failed', attempts = ?, last_attempt_at = ?, last_response_code = ?, "
                + "last_error_code = ?, next_attempt_at = null where id = ? and status = 'queued'", attempts, time(at), responseCode, errorCode, id) == 1;
    }

    @Override
    public boolean markSkipped(UUID id, String reason) {
        return jdbc.update("update notification.webhook_delivery set status = 'skipped', attempts = 0, last_error_code = ?, next_attempt_at = null "
                + "where id = ? and status = 'queued'", reason, id) == 1;
    }

    @Override
    public List<WebhookDelivery> findBySeller(WebhookDeliveryFilter filter) {
        StringBuilder sql = new StringBuilder("select id, event_id, event_type, order_number, status, attempts, last_response_code, last_attempt_at, "
                + "next_attempt_at, created_at from notification.webhook_delivery where seller_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(filter.sellerId());
        if (filter.statuses().isPresent()) {
            List<String> statuses = filter.statuses().get();
            sql.append(" and status in (").append(String.join(", ", java.util.Collections.nCopies(statuses.size(), "?"))).append(")");
            args.addAll(statuses);
        }
        if (filter.after().isPresent()) {
            sql.append(" and (created_at, id) < (?, ?)");
            args.add(time(filter.after().get().createdAt()));
            args.add(filter.after().get().id());
        }
        sql.append(" order by created_at desc, id desc limit ?");
        args.add(filter.limit() + 1);
        return jdbc.query(sql.toString(), LISTED, args.toArray());
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
