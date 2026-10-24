package dgm.payment.payments.repository;

import dgm.kit.boot.ModuleDatabases;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Обработанные уведомления шлюза в PostgreSQL под ролью {@code app_payments}. */
@Component
public final class JdbcPaymentEventRepository implements PaymentEventRepository {

    private final JdbcTemplate jdbc;

    public JdbcPaymentEventRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(JdbcPaymentRepository.MODULE);
    }

    @Override
    public boolean exists(String gatewayPaymentId, String eventType) {
        Integer n = jdbc.queryForObject("select count(*) from payments.payment_event where gateway_payment_id = ? and event_type = ?", Integer.class,
                gatewayPaymentId, eventType);
        return n != null && n > 0;
    }

    @Override
    public boolean insertIfAbsent(String gatewayPaymentId, String eventType, String gatewayEventId, UUID paymentId, String outcome, Instant occurredAt,
                                  Instant receivedAt) {
        return jdbc.update("insert into payments.payment_event (gateway_payment_id, event_type, gateway_event_id, payment_id, outcome, occurred_at, received_at) "
                + "values (?, ?, ?, ?, ?, ?, ?)", gatewayPaymentId, eventType, gatewayEventId,
                paymentId, outcome, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC), OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC)) == 1;
    }
}
