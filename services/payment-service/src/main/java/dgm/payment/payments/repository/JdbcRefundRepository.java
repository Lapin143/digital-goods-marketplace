package dgm.payment.payments.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.kit.jdbc.RowClaim;
import dgm.payment.payments.domain.ManualRefund;
import dgm.payment.payments.domain.RefundAttempt;
import dgm.payment.payments.domain.RefundPayment;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Возвраты в PostgreSQL под ролью {@code app_payments}. */
@Component
public final class JdbcRefundRepository implements RefundRepository {

    private static final String PAYMENT_COLUMNS = "id, order_id, gateway_payment_id, amount, currency, status, refund_reason, gateway_refund_id, "
            + "in_admin_queue, refund_escalated_at, refunded_at";
    private static final String ATTEMPT_COLUMNS = "id, payment_id, attempt_no, status, next_attempt_at, error_code";

    private static final RowMapper<RefundPayment> PAYMENT = (rs, row) -> new RefundPayment(rs.getObject("id", UUID.class),
            rs.getObject("order_id", UUID.class), rs.getString("gateway_payment_id"), rs.getLong("amount"), rs.getString("currency").trim(),
            rs.getString("status"), rs.getString("refund_reason"), rs.getString("gateway_refund_id"), rs.getBoolean("in_admin_queue"),
            instant(rs.getObject("refund_escalated_at", OffsetDateTime.class)), instant(rs.getObject("refunded_at", OffsetDateTime.class)));

    private static final RowMapper<RefundAttempt> ATTEMPT = (rs, row) -> new RefundAttempt(rs.getObject("id", UUID.class),
            rs.getObject("payment_id", UUID.class), rs.getInt("attempt_no"), rs.getString("status"),
            instant(rs.getObject("next_attempt_at", OffsetDateTime.class)), rs.getString("error_code"));

    /** Очередь исполнителя: открытые попытки, срок которых наступил; аренда переводит попытку в {@code in_progress} (ix_refund_attempt_due). */
    private static final RowClaim DUE = RowClaim.on("payments.refund_attempt")
            .where("status in ('pending', 'in_progress')")
            .dueColumn("next_attempt_at")
            .orderBy("next_attempt_at, id")
            .alsoSet("status = 'in_progress'")
            .returning("refund_attempt." + String.join(", refund_attempt.", ATTEMPT_COLUMNS.split(", ")))
            .build();

    private final JdbcTemplate jdbc;

    public JdbcRefundRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(JdbcPaymentRepository.MODULE);
    }

    @Override
    public Optional<RefundPayment> findById(UUID paymentId) {
        return jdbc.query("select " + PAYMENT_COLUMNS + " from payments.payment where id = ?", PAYMENT, paymentId).stream().findFirst();
    }

    @Override
    public Optional<RefundPayment> lockByOrder(UUID orderId) {
        return jdbc.query("select " + PAYMENT_COLUMNS + " from payments.payment where order_id = ? for update", PAYMENT, orderId).stream().findFirst();
    }

    @Override
    public Optional<RefundPayment> lockById(UUID paymentId) {
        return jdbc.query("select " + PAYMENT_COLUMNS + " from payments.payment where id = ? for update", PAYMENT, paymentId).stream().findFirst();
    }

    @Override
    public boolean recordReason(UUID paymentId, String reason) {
        return jdbc.update("update payments.payment set refund_reason = ? where id = ? and status = 'confirmed' and refund_reason is null", reason,
                paymentId) == 1;
    }

    @Override
    public boolean insertAttempt(RefundAttempt attempt, Instant at) {
        return jdbc.update("insert into payments.refund_attempt (id, payment_id, attempt_no, status, next_attempt_at, created_at) values (?, ?, ?, ?, ?, ?) "
                + "on conflict do nothing", attempt.id(), attempt.paymentId(), attempt.attemptNo(), attempt.status(), time(attempt.nextAttemptAt()), time(at)) == 1;
    }

    @Override
    public List<RefundAttempt> claimDue(Instant now, Duration lease, int limit) {
        return DUE.claim(jdbc, now, lease, limit, ATTEMPT);
    }

    @Override
    public boolean finishAttempt(UUID attemptId, String status, String errorCode, Instant at) {
        return jdbc.update("update payments.refund_attempt set status = ?, error_code = ?, next_attempt_at = null, finished_at = ? "
                + "where id = ? and status in ('pending', 'in_progress', 'processing')", status, errorCode, time(at), attemptId) == 1;
    }

    @Override
    public int closeOpenAttempts(UUID paymentId, Instant at) {
        return jdbc.update("update payments.refund_attempt set status = 'succeeded', next_attempt_at = null, finished_at = ? "
                + "where payment_id = ? and status in ('pending', 'in_progress', 'processing')", time(at), paymentId);
    }

    @Override
    public boolean attachRefundId(UUID paymentId, String gatewayRefundId) {
        return jdbc.update("update payments.payment set gateway_refund_id = ? where id = ? and status = 'confirmed' and gateway_refund_id is null",
                gatewayRefundId, paymentId) == 1;
    }

    @Override
    public Optional<RefundPayment> markRefunded(UUID paymentId, String gatewayRefundId, Instant at) {
        return jdbc.query("update payments.payment set status = 'refunded', refunded_at = ?, in_admin_queue = false, "
                + "gateway_refund_id = coalesce(gateway_refund_id, ?) where id = ? and status = 'confirmed' returning " + PAYMENT_COLUMNS, PAYMENT,
                time(at), gatewayRefundId, paymentId).stream().findFirst();
    }

    @Override
    public boolean escalate(UUID paymentId, Instant at) {
        return jdbc.update("update payments.payment set in_admin_queue = true, refund_escalated_at = ? where id = ? and status = 'confirmed' "
                + "and not in_admin_queue", time(at), paymentId) == 1;
    }

    @Override
    public int attemptCount(UUID paymentId) {
        Integer count = jdbc.queryForObject("select count(*) from payments.refund_attempt where payment_id = ?", Integer.class, paymentId);
        return count == null ? 0 : count;
    }

    @Override
    public List<RefundPayment> awaitingConfirmation(Instant processingBefore, int limit) {
        return jdbc.query("select " + prefixed("p") + " from payments.payment p where p.status = 'confirmed' and exists (select 1 from payments.refund_attempt a "
                + "where a.payment_id = p.id and a.status = 'processing' and a.finished_at <= ?) order by p.updated_at, p.id limit ?", PAYMENT,
                time(processingBefore), limit);
    }

    @Override
    public List<ManualRefund> queue(Optional<QueueCursor> after, int limit) {
        String select = "select p.id, p.order_id, p.amount, p.currency, p.refund_reason, p.refund_escalated_at, "
                + "(select count(*) from payments.refund_attempt a where a.payment_id = p.id) as attempts from payments.payment p where p.in_admin_queue";
        RowMapper<ManualRefund> mapper = (rs, row) -> new ManualRefund(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getLong("amount"), rs.getString("currency").trim(), rs.getString("refund_reason"), rs.getInt("attempts"),
                instant(rs.getObject("refund_escalated_at", OffsetDateTime.class)));
        if (after.isEmpty()) {
            return jdbc.query(select + " order by p.refund_escalated_at, p.id limit ?", mapper, limit);
        }
        return jdbc.query(select + " and (p.refund_escalated_at, p.id) > (?, ?) order by p.refund_escalated_at, p.id limit ?", mapper,
                time(after.get().escalatedAt()), after.get().paymentId(), limit);
    }

    private static String prefixed(String alias) {
        return alias + "." + String.join(", " + alias + ".", PAYMENT_COLUMNS.split(", "));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime time(Instant at) {
        return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
