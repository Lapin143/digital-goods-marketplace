package dgm.platform.support.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.platform.support.domain.OrderView;
import dgm.platform.support.domain.ResolvedTicket;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Обращения и модель чтения заказа в PostgreSQL под ролью {@code app_support}. Единственность открытого обращения держит частичный
 * уникальный индекс {@code uq_ticket_order_id_open} (INV-21): гонка двух событий решается {@code ON CONFLICT DO NOTHING}, а не проверкой
 * перед вставкой.
 */
@Component
public final class JdbcSupportRepository implements SupportRepository {

    static final String MODULE = "support";

    private final JdbcTemplate jdbc;

    public JdbcSupportRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public void insertPaid(UUID orderId, long orderNumber, UUID buyerId, Instant at) {
        jdbc.update("insert into support.order_view (order_id, order_number, buyer_id, order_status, updated_at) values (?, ?, ?, 'paid', ?) "
                + "on conflict (order_id) do nothing", orderId, orderNumber, buyerId, utc(at));
    }

    @Override
    public void upsertIssued(UUID orderId, long orderNumber, UUID buyerId, Instant issuedAt, Instant at) {
        jdbc.update("insert into support.order_view (order_id, order_number, buyer_id, order_status, issued_at, updated_at) "
                + "values (?, ?, ?, 'issued', ?, ?) on conflict (order_id) do update set order_status = 'issued', "
                + "issued_at = coalesce(support.order_view.issued_at, excluded.issued_at), updated_at = excluded.updated_at",
                orderId, orderNumber, buyerId, utc(issuedAt), utc(at));
    }

    @Override
    public Optional<OrderView> findOrderView(UUID orderId) {
        return jdbc.query("select order_id, order_number, buyer_id, order_status, issued_at, delivery_status, delivery_updated_at "
                + "from support.order_view where order_id = ?", (rs, row) -> map(rs), orderId).stream().findFirst();
    }

    @Override
    public boolean updateDelivery(UUID orderId, String deliveryStatus, Instant eventTime, Instant at) {
        return jdbc.update("update support.order_view set delivery_status = ?, delivery_updated_at = ?, updated_at = ? "
                + "where order_id = ? and (delivery_updated_at is null or delivery_updated_at <= ?)",
                deliveryStatus, utc(eventTime), utc(at), orderId, utc(eventTime)) == 1;
    }

    @Override
    public boolean insertSystemTicket(UUID ticketId, UUID orderId, UUID buyerId, String reason, Instant at) {
        return jdbc.update("insert into support.ticket (id, order_id, buyer_id, source, reason, created_at, updated_at) "
                + "values (?, ?, ?, 'system', ?, ?, ?) on conflict (order_id) where status in ('created', 'in_progress') do nothing",
                ticketId, orderId, buyerId, reason, utc(at), utc(at)) == 1;
    }

    @Override
    public List<ResolvedTicket> resolveOpenTickets(UUID orderId, Instant at) {
        return jdbc.query("update support.ticket set status = 'resolved', resolved_by = 'system', resolved_at = ? "
                + "where order_id = ? and status in ('created', 'in_progress') returning id, buyer_id",
                (rs, row) -> new ResolvedTicket(rs.getObject("id", UUID.class), rs.getObject("buyer_id", UUID.class)), utc(at), orderId);
    }

    private static OrderView map(ResultSet rs) throws SQLException {
        return new OrderView(rs.getObject("order_id", UUID.class), rs.getLong("order_number"), rs.getObject("buyer_id", UUID.class),
                rs.getString("order_status"), instant(rs, "issued_at"), Optional.ofNullable(rs.getString("delivery_status")),
                instant(rs, "delivery_updated_at"));
    }

    private static Optional<Instant> instant(ResultSet rs, String column) throws SQLException {
        return Optional.ofNullable(rs.getObject(column, OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
