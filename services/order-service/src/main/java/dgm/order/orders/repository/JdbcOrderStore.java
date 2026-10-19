package dgm.order.orders.repository;

import dgm.kit.boot.ModuleDatabases;
import dgm.order.orders.domain.NewOrder;
import dgm.order.orders.domain.StoredOrder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Запись заказов в PostgreSQL под ролью {@code app_orders}. Снимки заказа неизменны (триггер {@code trg_orders_immutable}), переходы статуса
 * проверяет триггер {@code trg_orders_status_transition}, поэтому недопустимый переход не пройдёт даже при ошибке в коде.
 */
@Component
public final class JdbcOrderStore implements OrderStore {

    /** Имя модуля базы в {@code dgm.db.modules}. */
    public static final String MODULE = "orders";

    private static final String COLUMNS = "id, number, buyer_id, seller_id, product_id, product_title, quantity, unit_price, amount, commission, "
            + "currency, status, cancel_reason, delivery_address, reserve_until, session_until, payment_session_url, created_at, paid_at, issued_at";

    private static final RowMapper<StoredOrder> ORDER = (rs, row) -> new StoredOrder(rs.getObject("id", UUID.class), rs.getLong("number"),
            rs.getObject("buyer_id", UUID.class), rs.getObject("seller_id", UUID.class), rs.getObject("product_id", UUID.class),
            rs.getString("product_title"), rs.getInt("quantity"), rs.getLong("unit_price"), rs.getLong("amount"), rs.getLong("commission"),
            rs.getString("currency").trim(), rs.getString("status"), rs.getString("cancel_reason"), rs.getString("delivery_address"),
            instant(rs.getObject("reserve_until", OffsetDateTime.class)), instant(rs.getObject("session_until", OffsetDateTime.class)),
            rs.getString("payment_session_url"), instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("paid_at", OffsetDateTime.class)), instant(rs.getObject("issued_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcOrderStore(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public void lockBuyer(UUID buyerId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, buyerId.toString());
    }

    @Override
    public int countUnpaid(UUID buyerId) {
        Integer count = jdbc.queryForObject("select count(*) from orders.orders where buyer_id = ? and status in ('created', 'awaiting_payment')",
                Integer.class, buyerId);
        return count == null ? 0 : count;
    }

    @Override
    public void insert(NewOrder o) {
        jdbc.update("insert into orders.orders (id, buyer_id, seller_id, product_id, product_title, quantity, unit_price, amount, commission_rate_bp, "
                + "commission, currency, delivery_address, status, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'created', ?)",
                o.id(), o.buyerId(), o.sellerId(), o.productId(), o.productTitle(), o.quantity(), o.unitPrice(), o.amount(), o.commissionRateBp(),
                o.commission(), o.currency(), o.deliveryAddress(), time(o.createdAt()));
    }

    @Override
    public Optional<StoredOrder> find(UUID orderId) {
        return jdbc.query("select " + COLUMNS + " from orders.orders where id = ?", ORDER, orderId).stream().findFirst();
    }

    @Override
    public Optional<StoredOrder> lock(UUID orderId) {
        return jdbc.query("select " + COLUMNS + " from orders.orders where id = ? for update", ORDER, orderId).stream().findFirst();
    }

    @Override
    public boolean awaitPayment(UUID orderId, Instant reserveUntil, Instant sessionUntil, String paymentSessionUrl) {
        return jdbc.update("update orders.orders set status = 'awaiting_payment', reserve_until = ?, session_until = ?, payment_session_url = ? "
                + "where id = ? and status = 'created'", time(reserveUntil), time(sessionUntil), paymentSessionUrl, orderId) == 1;
    }

    @Override
    public boolean cancel(UUID orderId, String from, String reason) {
        return jdbc.update("update orders.orders set status = 'cancelled', cancel_reason = ? where id = ? and status = ?", reason, orderId, from) == 1;
    }

    @Override
    public boolean pay(UUID orderId, String from, Instant paidAt) {
        return jdbc.update("update orders.orders set status = 'paid', cancel_reason = null, paid_at = ? where id = ? and status = ?",
                time(paidAt), orderId, from) == 1;
    }

    @Override
    public boolean refund(UUID orderId) {
        return jdbc.update("update orders.orders set status = 'refunded' where id = ? and status = 'cancelled'", orderId) == 1;
    }

    @Override
    public boolean issue(UUID orderId, Instant issuedAt) {
        return jdbc.update("update orders.orders set status = 'issued', issued_at = ? where id = ? and status = 'paid'", time(issuedAt), orderId) == 1;
    }

    @Override
    public int anonymizeAddress(UUID buyerId, String replacement) {
        return jdbc.update("update orders.orders set delivery_address = ? where buyer_id = ? and delivery_address <> ?", replacement, buyerId, replacement);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime time(Instant at) {
        return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
