package dgm.order.orders.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderFilter;
import dgm.order.orders.domain.OrderSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Заказы в PostgreSQL. История и карточка заказа покупателя (читатель истории, {@code history-reader}) читаются под ролью {@code app_orders_read}: у неё
 * только {@code SELECT} на {@code orders.orders}, своё ограничение 2 соединения и свой пул, запись база отвергает (ADR-017, Р-163). Запрос истории
 * опирается на индекс {@code ix_orders_buyer_id (buyer_id, created_at desc, id desc)}. Проходы сторожа заказов это не история: они читают под ролью
 * {@code app_orders}, как и записывающая часть сервиса.
 */
@Component
public final class JdbcOrderRepository implements OrderRepository {

    /** Имя модуля базы читателя истории в {@code dgm.db.modules}: роль {@code app_orders_read}, пул 2. */
    public static final String READ_MODULE = "orders-read";

    private static final String COLUMNS = "id, number, product_id, product_title, quantity, unit_price, amount, currency, status, "
            + "cancel_reason, delivery_channel, delivery_address, reserve_until, session_until, created_at, paid_at, issued_at";

    /** Читатель истории: роль только на чтение. */
    private final JdbcTemplate history;
    /** Проходы сторожа: роль модуля заказов. */
    private final JdbcTemplate watchdog;

    public JdbcOrderRepository(ModuleDatabases databases) {
        this.history = databases.jdbc(READ_MODULE);
        this.watchdog = databases.jdbc(JdbcOrderStore.MODULE);
    }

    @Override
    public List<Order> findByBuyer(OrderFilter f) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from orders.orders where buyer_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(f.buyerId());
        f.statuses().ifPresent(statuses -> {
            sql.append(" and status in (").append(String.join(", ", Collections.nCopies(statuses.size(), "?"))).append(')');
            args.addAll(statuses);
        });
        f.createdFrom().ifPresent(from -> {
            sql.append(" and created_at >= ?");
            args.add(utc(from));
        });
        f.createdTo().ifPresent(to -> {
            sql.append(" and created_at < ?");
            args.add(utc(to));
        });
        f.after().ifPresent(after -> {
            sql.append(" and (created_at, id) < (?, ?)");
            args.add(utc(after.createdAt()));
            args.add(after.id());
        });
        sql.append(" order by created_at desc, id desc limit ?");
        args.add(f.limit() + 1);
        return history.query(sql.toString(), (rs, row) -> map(rs), args.toArray());
    }

    @Override
    public Optional<Order> findOwned(UUID orderId, UUID buyerId) {
        return history.query("select " + COLUMNS + " from orders.orders where id = ? and buyer_id = ?", (rs, row) -> map(rs), orderId, buyerId).stream()
                .findFirst();
    }

    @Override
    public Optional<OrderSession> findSession(UUID orderId, UUID buyerId) {
        return history.query("select id, status, cancel_reason, payment_session_url, session_until from orders.orders where id = ? and buyer_id = ?",
                (rs, row) -> new OrderSession(rs.getObject("id", UUID.class), rs.getString("status"),
                        Optional.ofNullable(rs.getString("cancel_reason")), Optional.ofNullable(rs.getString("payment_session_url")),
                        instant(rs, "session_until")), orderId, buyerId).stream().findFirst();
    }

    @Override
    public List<UUID> findCreatedBefore(Instant border, int limit) {
        return watchdog.queryForList("select id from orders.orders where status = 'created' and created_at < ? order by created_at, id limit ?", UUID.class,
                utc(border), limit);
    }

    @Override
    public List<UUID> findAwaitingReserveEndedBefore(Instant border, int limit) {
        return watchdog.queryForList("select id from orders.orders where status = 'awaiting_payment' and reserve_until < ? order by reserve_until, id limit ?",
                UUID.class, utc(border), limit);
    }

    private static Order map(ResultSet rs) throws SQLException {
        return new Order(
                rs.getObject("id", UUID.class),
                rs.getLong("number"),
                rs.getObject("product_id", UUID.class),
                rs.getString("product_title"),
                rs.getInt("quantity"),
                rs.getLong("unit_price"),
                rs.getLong("amount"),
                rs.getString("currency").trim(),
                rs.getString("status"),
                Optional.ofNullable(rs.getString("cancel_reason")),
                rs.getString("delivery_channel"),
                rs.getString("delivery_address"),
                instant(rs, "reserve_until"),
                instant(rs, "session_until"),
                instant(rs, "created_at").orElseThrow(),
                instant(rs, "paid_at"),
                instant(rs, "issued_at"));
    }

    private static Optional<Instant> instant(ResultSet rs, String column) throws SQLException {
        return Optional.ofNullable(rs.getObject(column, OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
