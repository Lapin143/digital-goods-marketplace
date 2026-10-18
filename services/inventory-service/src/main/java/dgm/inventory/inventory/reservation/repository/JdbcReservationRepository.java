package dgm.inventory.inventory.reservation.repository;

import dgm.inventory.inventory.registry.JdbcProductCopyRepository;
import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.kit.boot.ModuleDatabases;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Резервы в PostgreSQL под ролью {@code app_inventory}. */
@Component
public final class JdbcReservationRepository implements ReservationRepository {

    private static final String COLUMNS = "id, order_id, product_id, quantity, status, expires_at, release_reason";
    private static final RowMapper<Reservation> MAPPER = (rs, row) -> new Reservation(rs.getObject("id", UUID.class),
            rs.getObject("order_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getInt("quantity"), rs.getString("status"),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getString("release_reason"));

    private final JdbcTemplate jdbc;

    public JdbcReservationRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(JdbcProductCopyRepository.MODULE);
    }

    @Override
    public Optional<Reservation> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from inventory.reservation where id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<Reservation> latestByOrder(UUID orderId) {
        return jdbc.query("select " + COLUMNS + " from inventory.reservation where order_id = ? order by created_at desc, id desc limit 1",
                MAPPER, orderId).stream().findFirst();
    }

    @Override
    public Optional<Reservation> activeByOrder(UUID orderId) {
        return jdbc.query("select " + COLUMNS + " from inventory.reservation where order_id = ? and status = 'active'", MAPPER, orderId)
                .stream().findFirst();
    }

    @Override
    public void insert(Reservation reservation, Instant createdAt) {
        jdbc.update("insert into inventory.reservation (id, order_id, product_id, quantity, status, expires_at, created_at) values (?, ?, ?, ?, 'active', ?, ?)",
                reservation.id(), reservation.orderId(), reservation.productId(), reservation.quantity(), time(reservation.expiresAt()), time(createdAt));
    }

    @Override
    public Optional<Reservation> releaseIfDue(UUID id, Instant now) {
        return jdbc.query("update inventory.reservation set status = 'released', release_reason = 'expired', released_at = ? "
                + "where id = ? and status = 'active' and expires_at <= ? returning " + COLUMNS, MAPPER, time(now), id, time(now)).stream().findFirst();
    }

    @Override
    public Optional<Reservation> release(UUID id, String reason, Instant at) {
        return jdbc.query("update inventory.reservation set status = 'released', release_reason = ?, released_at = ? "
                + "where id = ? and status = 'active' returning " + COLUMNS, MAPPER, reason, time(at), id).stream().findFirst();
    }

    @Override
    public Optional<Reservation> markUsed(UUID id, Instant at) {
        return jdbc.query("update inventory.reservation set status = 'used', used_at = ? where id = ? and status = 'active' returning " + COLUMNS,
                MAPPER, time(at), id).stream().findFirst();
    }

    @Override
    public List<UUID> overdue(Instant dueBefore, int limit) {
        return jdbc.query("select id from inventory.reservation where status = 'active' and expires_at <= ? order by expires_at, id limit ?",
                (rs, row) -> rs.getObject("id", UUID.class), time(dueBefore), limit);
    }

    @Override
    public List<Reservation> activeAfter(UUID after, int limit) {
        return jdbc.query("select " + COLUMNS + " from inventory.reservation where status = 'active' and id > ? order by id limit ?", MAPPER, after, limit);
    }

    private static OffsetDateTime time(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
