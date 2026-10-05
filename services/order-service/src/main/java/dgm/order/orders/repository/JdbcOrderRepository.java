package dgm.order.orders.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.order.orders.domain.Order;
import dgm.order.orders.domain.OrderFilter;
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

/** Заказы в PostgreSQL. Запрос опирается на индекс {@code ix_orders_buyer_id (buyer_id, created_at desc, id desc)}. */
@Component
public final class JdbcOrderRepository implements OrderRepository {

    private final JdbcTemplate jdbc;

    public JdbcOrderRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc("orders");
    }

    @Override
    public List<Order> findByBuyer(OrderFilter f) {
        StringBuilder sql = new StringBuilder("select id, product_id, product_title, quantity, unit_price, amount, currency, status, "
                + "cancel_reason, delivery_channel, delivery_address, reserve_until, session_until, created_at, paid_at, issued_at "
                + "from orders.orders where buyer_id = ?");
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
        return jdbc.query(sql.toString(), (rs, row) -> map(rs), args.toArray());
    }

    private static Order map(ResultSet rs) throws SQLException {
        return new Order(
                rs.getObject("id", UUID.class),
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
