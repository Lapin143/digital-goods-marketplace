package dgm.delivery.delivery.repository;

import dgm.delivery.delivery.domain.QueuedDelivery;
import dgm.kit.boot.ModuleDatabases;
import dgm.kit.jdbc.RowClaim;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Выдачи в PostgreSQL под ролью {@code app_delivery}. */
@Component
public final class JdbcDeliveryRepository implements DeliveryRepository {

    /** Модуль базы ({@code dgm.db.modules.delivery}). */
    public static final String MODULE = "delivery";

    private static final String QUEUED_COLUMNS = "id, order_id, buyer_id, kind, address, product_title, quantity, attempt_no";

    private static final RowMapper<QueuedDelivery> QUEUED = (rs, row) -> new QueuedDelivery(rs.getObject("id", UUID.class),
            rs.getObject("order_id", UUID.class), rs.getObject("buyer_id", UUID.class), rs.getString("kind"), rs.getString("address"),
            rs.getString("product_title"), rs.getInt("quantity"), rs.getInt("attempt_no"));

    /** Очередь диспетчера: выдачи «в очереди», срок которых наступил; аренда сдвигает {@code next_attempt_at} (ix_delivery_dispatch). */
    private static final RowClaim DUE = RowClaim.on("delivery.delivery")
            .where("status = 'queued'")
            .dueColumn("next_attempt_at")
            .orderBy("next_attempt_at, id")
            .returning(QUEUED_COLUMNS)
            .build();

    private final JdbcTemplate jdbc;

    public JdbcDeliveryRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public boolean insertPrimary(UUID id, UUID orderId, UUID buyerId, String address, String productTitle, int quantity, Instant now) {
        return jdbc.update("insert into delivery.delivery (id, order_id, buyer_id, kind, channel, address, product_title, quantity, attempt_no, "
                + "next_attempt_at, status, created_at, updated_at) values (?, ?, ?, 'primary', 'email', ?, ?, ?, 0, ?, 'queued', ?, ?) on conflict do nothing",
                id, orderId, buyerId, address, productTitle, quantity, time(now), time(now), time(now)) == 1;
    }

    @Override
    public boolean insertWatch(UUID orderId, Instant deadline, Instant now) {
        return jdbc.update("insert into delivery.delivery_watch (order_id, deadline_at, status, created_at) values (?, ?, 'open', ?) on conflict do nothing",
                orderId, time(deadline), time(now)) == 1;
    }

    @Override
    public List<QueuedDelivery> claimDue(Instant now, Duration lease, int limit) {
        return DUE.claim(jdbc, now, lease, limit, QUEUED);
    }

    @Override
    public boolean markSent(UUID id, int expectedAttempts, Instant startedAt, Instant finishedAt) {
        int attemptNo = expectedAttempts + 1;
        boolean changed = jdbc.update("update delivery.delivery set status = 'sent', attempt_no = ?, sent_at = ?, next_attempt_at = null, "
                + "last_error_code = null where id = ? and status = 'queued' and attempt_no = ?", attemptNo, time(finishedAt), id, expectedAttempts) == 1;
        if (changed) {
            insertAttempt(id, attemptNo, "accepted", null, startedAt, finishedAt);
        }
        return changed;
    }

    @Override
    public boolean markRetry(UUID id, int expectedAttempts, String errorCode, Instant nextAttemptAt, Instant startedAt, Instant finishedAt) {
        int attemptNo = expectedAttempts + 1;
        boolean changed = jdbc.update("update delivery.delivery set attempt_no = ?, next_attempt_at = ?, last_error_code = ? "
                + "where id = ? and status = 'queued' and attempt_no = ?", attemptNo, time(nextAttemptAt), errorCode, id, expectedAttempts) == 1;
        if (changed) {
            insertAttempt(id, attemptNo, "retryable_error", errorCode, startedAt, finishedAt);
        }
        return changed;
    }

    @Override
    public boolean markFailed(UUID id, int expectedAttempts, String outcome, String errorCode, String failReason, Instant startedAt, Instant finishedAt) {
        int attemptNo = expectedAttempts + 1;
        boolean changed = jdbc.update("update delivery.delivery set status = 'failed', attempt_no = ?, next_attempt_at = null, fail_reason = ?, "
                + "last_error_code = ? where id = ? and status = 'queued' and attempt_no = ?", attemptNo, failReason, errorCode, id, expectedAttempts) == 1;
        if (changed) {
            insertAttempt(id, attemptNo, outcome, errorCode, startedAt, finishedAt);
        }
        return changed;
    }

    @Override
    public int anonymizeOpen(UUID buyerId, String replacement) {
        return jdbc.update("update delivery.delivery set address = ? where buyer_id = ? and status in ('queued', 'sent') and address <> ?",
                replacement, buyerId, replacement);
    }

    private void insertAttempt(UUID deliveryId, int attemptNo, String outcome, String errorCode, Instant startedAt, Instant finishedAt) {
        jdbc.update("insert into delivery.delivery_attempt (delivery_id, attempt_no, outcome, error_code, started_at, finished_at) values (?, ?, ?, ?, ?, ?)",
                deliveryId, attemptNo, outcome, errorCode, time(startedAt), time(finishedAt));
    }

    private static OffsetDateTime time(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
