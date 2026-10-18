package dgm.payment.payments.repository;

import dgm.kit.boot.ModuleDatabases;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Несопоставленные уведомления шлюза в PostgreSQL под ролью {@code app_payments}. */
@Component
public final class JdbcUnmatchedNotificationRepository implements UnmatchedNotificationRepository {

    private static final String COLUMNS = "id, gateway_payment_id, event_type, gateway_event_id, order_id, occurred_at, received_at";
    private static final RowMapper<Unmatched> MAPPER = (rs, row) -> new Unmatched(rs.getObject("id", UUID.class), rs.getString("gateway_payment_id"),
            rs.getString("event_type"), rs.getString("gateway_event_id"), rs.getObject("order_id", UUID.class),
            rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getObject("received_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public JdbcUnmatchedNotificationRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(JdbcPaymentRepository.MODULE);
    }

    @Override
    public boolean insertIfAbsent(Unmatched n) {
        return jdbc.update("insert into payments.unmatched_notification (id, gateway_payment_id, event_type, gateway_event_id, order_id, occurred_at, received_at) "
                + "values (?, ?, ?, ?, ?, ?, ?) on conflict (gateway_payment_id, event_type) do nothing", n.id(), n.gatewayPaymentId(), n.eventType(),
                n.gatewayEventId(), n.orderId(), time(n.occurredAt()), time(n.receivedAt())) == 1;
    }

    @Override
    public List<Unmatched> pending(int limit) {
        return jdbc.query("select " + COLUMNS + " from payments.unmatched_notification where matched_at is null order by received_at, id limit ?", MAPPER, limit);
    }

    @Override
    public void markMatched(UUID id, Instant at) {
        // ck_unmatched_notification_alert: оповещение действует, пока уведомление не сопоставлено; сопоставленное оповещения не несёт
        jdbc.update("update payments.unmatched_notification set matched_at = ?, alert_sent_at = null where id = ? and matched_at is null", time(at), id);
    }

    @Override
    public List<Unmatched> overdueWithoutAlert(Instant receivedBefore, int limit) {
        return jdbc.query("select " + COLUMNS + " from payments.unmatched_notification where matched_at is null and alert_sent_at is null "
                + "and received_at < ? order by received_at, id limit ?", MAPPER, time(receivedBefore), limit);
    }

    @Override
    public void markAlerted(UUID id, Instant at) {
        jdbc.update("update payments.unmatched_notification set alert_sent_at = ? where id = ? and matched_at is null", time(at), id);
    }

    private static OffsetDateTime time(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
