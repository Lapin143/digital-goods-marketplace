package dgm.platform.support.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.platform.support.domain.OrderView;
import dgm.platform.support.domain.QueueDepth;
import dgm.platform.support.domain.QueueFilter;
import dgm.platform.support.domain.ResolvedTicket;
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.domain.SupportTicket;
import dgm.platform.support.domain.TicketFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
    private static final String TICKET_COLUMNS = "id, order_id, buyer_id, source, reason, status, created_at, resolved_at, resolved_by, action";
    private static final String STAFF_SELECT = "select t.id, t.order_id, v.order_number, t.buyer_id, t.source, t.reason, t.status, t.operator_id, "
            + "v.order_status, v.issued_at, v.delivery_status, t.created_at, t.taken_at, t.resolved_at "
            + "from support.ticket t join support.order_view v on v.order_id = t.order_id";

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

    @Override
    public boolean insertBuyerTicket(UUID ticketId, UUID orderId, UUID buyerId, Instant at) {
        return jdbc.update("insert into support.ticket (id, order_id, buyer_id, source, reason, created_at, updated_at) "
                + "values (?, ?, ?, 'buyer', 'key_not_received', ?, ?) on conflict (order_id) where status in ('created', 'in_progress') do nothing",
                ticketId, orderId, buyerId, utc(at), utc(at)) == 1;
    }

    @Override
    public Optional<SupportTicket> findTicket(UUID ticketId) {
        return jdbc.query("select " + TICKET_COLUMNS + " from support.ticket where id = ?", (rs, row) -> mapTicket(rs), ticketId).stream().findFirst();
    }

    @Override
    public List<SupportTicket> findTickets(TicketFilter filter) {
        StringBuilder sql = new StringBuilder("select " + TICKET_COLUMNS + " from support.ticket where buyer_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(filter.buyerId());
        filter.orderId().ifPresent(orderId -> {
            sql.append(" and order_id = ?");
            args.add(orderId);
        });
        if (!filter.statuses().isEmpty()) {
            sql.append(" and status in (").append("?, ".repeat(filter.statuses().size() - 1)).append("?)");
            args.addAll(filter.statuses());
        }
        filter.after().ifPresent(after -> {
            sql.append(" and (created_at, id) < (?, ?)");
            args.add(utc(after.createdAt()));
            args.add(after.id());
        });
        sql.append(" order by created_at desc, id desc limit ?");
        args.add(filter.limit() + 1);
        return jdbc.query(sql.toString(), (rs, row) -> mapTicket(rs), args.toArray());
    }

    @Override
    public List<StaffTicket> findQueue(QueueFilter filter) {
        StringBuilder sql = new StringBuilder(STAFF_SELECT).append(" where true");
        List<Object> args = new ArrayList<>();
        if (!filter.statuses().isEmpty()) {
            sql.append(" and t.status in (").append("?, ".repeat(filter.statuses().size() - 1)).append("?)");
            args.addAll(filter.statuses());
        }
        filter.operatorId().ifPresent(operatorId -> {
            sql.append(" and t.operator_id = ?");
            args.add(operatorId);
        });
        filter.orderNumber().ifPresent(number -> {
            sql.append(" and v.order_number = ?");
            args.add(number);
        });
        filter.after().ifPresent(after -> {
            sql.append(" and (t.created_at, t.id) > (?, ?)");
            args.add(utc(after.createdAt()));
            args.add(after.id());
        });
        sql.append(" order by t.created_at, t.id limit ?");
        args.add(filter.limit() + 1);
        return jdbc.query(sql.toString(), (rs, row) -> mapStaff(rs), args.toArray());
    }

    @Override
    public Optional<StaffTicket> findStaffTicket(UUID ticketId) {
        return jdbc.query(STAFF_SELECT + " where t.id = ?", (rs, row) -> mapStaff(rs), ticketId).stream().findFirst();
    }

    @Override
    public boolean takeTicket(UUID ticketId, UUID operatorId, Instant at) {
        return jdbc.update("update support.ticket set status = 'in_progress', operator_id = ?, taken_at = ? where id = ? and status = 'created'",
                operatorId, utc(at), ticketId) == 1;
    }

    @Override
    public boolean releaseTicket(UUID ticketId, UUID operatorId) {
        return jdbc.update("update support.ticket set status = 'created', operator_id = null, taken_at = null "
                + "where id = ? and status = 'in_progress' and operator_id = ?", ticketId, operatorId) == 1;
    }

    @Override
    public boolean markResend(UUID ticketId, UUID operatorId) {
        return jdbc.update("update support.ticket set action = 'resend' where id = ? and status = 'in_progress' and operator_id = ?", ticketId, operatorId) == 1;
    }

    @Override
    public QueueDepth queueDepth() {
        return jdbc.query("select count(*) filter (where status = 'created') as created, count(*) filter (where status = 'in_progress') as in_progress, "
                + "min(created_at) filter (where status = 'created') as oldest from support.ticket where status in ('created', 'in_progress')",
                (rs, row) -> new QueueDepth(rs.getLong("created"), rs.getLong("in_progress"), instant(rs, "oldest"))).get(0);
    }

    private static StaffTicket mapStaff(ResultSet rs) throws SQLException {
        return new StaffTicket(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getLong("order_number"),
                rs.getObject("buyer_id", UUID.class), rs.getString("source"), rs.getString("reason"), rs.getString("status"),
                Optional.ofNullable(rs.getObject("operator_id", UUID.class)), rs.getString("order_status"), instant(rs, "issued_at"),
                Optional.ofNullable(rs.getString("delivery_status")), instant(rs, "created_at").orElseThrow(), instant(rs, "taken_at"),
                instant(rs, "resolved_at"));
    }

    private static SupportTicket mapTicket(ResultSet rs) throws SQLException {
        return new SupportTicket(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("buyer_id", UUID.class),
                rs.getString("source"), rs.getString("reason"), rs.getString("status"), instant(rs, "created_at").orElseThrow(),
                instant(rs, "resolved_at"), Optional.ofNullable(rs.getString("resolved_by")), Optional.ofNullable(rs.getString("action")));
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
