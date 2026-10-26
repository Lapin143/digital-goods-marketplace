package dgm.platform.support.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.platform.support.domain.EmailChangeView;
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
    /** Состояние смены e-mail (без адреса: в карточки идёт только маска) присоединяется к обращению левым соединением по первичному ключу. */
    private static final String CHANGE_COLUMNS = "e.status as ec_status, e.phone_check as ec_phone_check, e.new_email_check as ec_new_email_check, "
            + "e.new_email_masked as ec_masked, e.started_at as ec_started_at, e.updated_at as ec_updated_at";
    private static final String CHANGE_JOIN = " left join support.email_change e on e.ticket_id = t.id";
    private static final String TICKET_SELECT = "select t.id, t.order_id, t.buyer_id, t.source, t.reason, t.status, t.created_at, t.resolved_at, "
            + "t.resolved_by, t.action, " + CHANGE_COLUMNS + " from support.ticket t" + CHANGE_JOIN;
    private static final String STAFF_SELECT = "select t.id, t.order_id, v.order_number, t.buyer_id, t.source, t.reason, t.status, t.operator_id, "
            + "v.order_status, v.issued_at, v.delivery_status, t.created_at, t.taken_at, t.resolved_at, " + CHANGE_COLUMNS
            + " from support.ticket t join support.order_view v on v.order_id = t.order_id" + CHANGE_JOIN;

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
        List<ResolvedTicket> resolved = jdbc.query("update support.ticket set status = 'resolved', resolved_by = 'system', resolved_at = ? "
                + "where order_id = ? and status in ('created', 'in_progress') returning id, buyer_id",
                (rs, row) -> new ResolvedTicket(rs.getObject("id", UUID.class), rs.getObject("buyer_id", UUID.class)), utc(at), orderId);
        // Смена e-mail, которая ещё ждёт кода, по закрытому обращению не нужна, а новый адрес в таблице после этого оставаться не должен (INV-44).
        // Смена с обоими подтверждениями ждёт завершения и остаётся: её доделает платформа (шаг 15)
        if (!resolved.isEmpty()) {
            jdbc.update("update support.email_change set status = 'failed', new_email = null, updated_at = ?, "
                    + "phone_check = case when phone_check = 'pending' then 'failed' else phone_check end, "
                    + "new_email_check = case when new_email_check = 'pending' then 'failed' else new_email_check end "
                    + "where ticket_id in (select id from support.ticket where order_id = ? and resolved_at = ? and resolved_by = 'system') "
                    + "and status in ('awaiting_phone_code', 'awaiting_email_code') and new_email_check <> 'passed'", utc(at), orderId, utc(at));
        }
        return resolved;
    }

    @Override
    public boolean insertBuyerTicket(UUID ticketId, UUID orderId, UUID buyerId, Instant at) {
        return jdbc.update("insert into support.ticket (id, order_id, buyer_id, source, reason, created_at, updated_at) "
                + "values (?, ?, ?, 'buyer', 'key_not_received', ?, ?) on conflict (order_id) where status in ('created', 'in_progress') do nothing",
                ticketId, orderId, buyerId, utc(at), utc(at)) == 1;
    }

    @Override
    public Optional<SupportTicket> findTicket(UUID ticketId) {
        return jdbc.query(TICKET_SELECT + " where t.id = ?", (rs, row) -> mapTicket(rs), ticketId).stream().findFirst();
    }

    @Override
    public List<SupportTicket> findTickets(TicketFilter filter) {
        StringBuilder sql = new StringBuilder(TICKET_SELECT).append(" where t.buyer_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(filter.buyerId());
        filter.orderId().ifPresent(orderId -> {
            sql.append(" and t.order_id = ?");
            args.add(orderId);
        });
        if (!filter.statuses().isEmpty()) {
            sql.append(" and t.status in (").append("?, ".repeat(filter.statuses().size() - 1)).append("?)");
            args.addAll(filter.statuses());
        }
        filter.after().ifPresent(after -> {
            sql.append(" and (t.created_at, t.id) < (?, ?)");
            args.add(utc(after.createdAt()));
            args.add(after.id());
        });
        sql.append(" order by t.created_at desc, t.id desc limit ?");
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
    public boolean markEmailChange(UUID ticketId, UUID operatorId) {
        return jdbc.update("update support.ticket set action = 'email_change' where id = ? and status = 'in_progress' and operator_id = ?", ticketId, operatorId) == 1;
    }

    @Override
    public boolean markCodeCheckPassed(UUID ticketId) {
        return jdbc.update("update support.ticket set code_check_result = 'passed' where id = ?", ticketId) == 1;
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
                instant(rs, "resolved_at"), changeView(rs));
    }

    /** Состояние смены e-mail из соединённых столбцов; пусто, если смена по обращению не начата. */
    private static Optional<EmailChangeView> changeView(ResultSet rs) throws SQLException {
        String status = rs.getString("ec_status");
        if (status == null) {
            return Optional.empty();
        }
        return Optional.of(new EmailChangeView(status, rs.getString("ec_phone_check"), rs.getString("ec_new_email_check"), rs.getString("ec_masked"),
                instant(rs, "ec_started_at").orElseThrow(), instant(rs, "ec_updated_at").orElseThrow()));
    }

    private static SupportTicket mapTicket(ResultSet rs) throws SQLException {
        return new SupportTicket(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("buyer_id", UUID.class),
                rs.getString("source"), rs.getString("reason"), rs.getString("status"), instant(rs, "created_at").orElseThrow(),
                instant(rs, "resolved_at"), Optional.ofNullable(rs.getString("resolved_by")), Optional.ofNullable(rs.getString("action")), changeView(rs));
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
