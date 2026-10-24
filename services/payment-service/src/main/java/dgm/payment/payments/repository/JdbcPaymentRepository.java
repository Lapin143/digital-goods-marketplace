package dgm.payment.payments.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.payment.payments.domain.Payment;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Платежи в PostgreSQL под ролью {@code app_payments}. */
@Component
public final class JdbcPaymentRepository implements PaymentRepository {

    /** Имя модуля базы: пул {@code dgm.db.modules.payments} и Outbox этого модуля. */
    public static final String MODULE = "payments";

    private static final String COLUMNS = "id, order_id, gateway_payment_id, amount, currency, session_url, session_expires_at, status";
    private static final RowMapper<Payment> MAPPER = (rs, row) -> {
        OffsetDateTime expires = rs.getObject("session_expires_at", OffsetDateTime.class);
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("gateway_payment_id"),
                rs.getLong("amount"), rs.getString("currency").trim(), rs.getString("session_url"),
                expires == null ? null : expires.toInstant(), rs.getString("status"));
    };

    private final JdbcTemplate jdbc;

    public JdbcPaymentRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public Optional<Payment> findByOrder(UUID orderId) {
        return jdbc.query("select " + COLUMNS + " from payments.payment where order_id = ?", MAPPER, orderId).stream().findFirst();
    }

    @Override
    public boolean insertIfAbsent(Payment payment, Instant at) {
        return jdbc.update("insert into payments.payment (id, order_id, amount, currency, status, created_at, updated_at) values (?, ?, ?, ?, 'created', ?, ?) "
                + "on conflict (order_id) do nothing", payment.id(), payment.orderId(), payment.amount(), payment.currency(), time(at), time(at)) == 1;
    }

    @Override
    public Optional<Payment> attachSession(UUID id, String gatewayPaymentId, String sessionUrl, Instant sessionExpiresAt) {
        // Внешний идентификатор мог прийти раньше из уведомления (ADR-015, шаг 3): тот же идентификатор не мешает записать адрес сессии
        return jdbc.query("update payments.payment set gateway_payment_id = ?, session_url = ?, session_expires_at = ? "
                + "where id = ? and session_url is null and (gateway_payment_id is null or gateway_payment_id = ?) returning " + COLUMNS, MAPPER,
                gatewayPaymentId, sessionUrl, time(sessionExpiresAt), id, gatewayPaymentId).stream().findFirst();
    }

    @Override
    public Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId) {
        return jdbc.query("select " + COLUMNS + " from payments.payment where gateway_payment_id = ?", MAPPER, gatewayPaymentId).stream().findFirst();
    }

    @Override
    public Optional<Payment> lockByOrder(UUID orderId) {
        return jdbc.query("select " + COLUMNS + " from payments.payment where order_id = ?", MAPPER, orderId).stream().findFirst();
    }

    @Override
    public Optional<Payment> adoptGatewayPaymentId(UUID id, String gatewayPaymentId) {
        return jdbc.query("update payments.payment set gateway_payment_id = ? where id = ? and gateway_payment_id is null returning " + COLUMNS,
                MAPPER, gatewayPaymentId, id).stream().findFirst();
    }

    @Override
    public Optional<Payment> confirm(UUID id, Instant at) {
        return jdbc.query("update payments.payment set status = 'confirmed', confirmed_at = ? where id = ? and status = 'created' returning " + COLUMNS,
                MAPPER, time(at), id).stream().findFirst();
    }

    @Override
    public Optional<Payment> reject(UUID id, Instant at) {
        return jdbc.query("update payments.payment set status = 'rejected', rejected_at = ? where id = ? and status = 'created' returning " + COLUMNS,
                MAPPER, time(at), id).stream().findFirst();
    }

    @Override
    public List<Payment> openBetween(Instant createdAfter, Instant createdBefore, int limit) {
        return jdbc.query("select " + COLUMNS + " from payments.payment where status = 'created' and gateway_payment_id is not null "
                + "and created_at >= ? and created_at < ? order by created_at, id limit ?", MAPPER, time(createdAfter), time(createdBefore), limit);
    }

    private static OffsetDateTime time(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
