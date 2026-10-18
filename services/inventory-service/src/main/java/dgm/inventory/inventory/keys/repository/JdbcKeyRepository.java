package dgm.inventory.inventory.keys.repository;

import dgm.inventory.inventory.crypto.KeyHasher;
import dgm.inventory.inventory.registry.JdbcProductCopyRepository;
import dgm.kit.boot.ModuleDatabases;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Ключи пула в PostgreSQL под ролью {@code app_inventory} (прав DELETE на таблицу у роли нет). */
@Component
public final class JdbcKeyRepository implements KeyRepository {

    /** Вставка пачками по 500 строк (ADR-009). */
    static final int CHUNK = 500;

    private final JdbcTemplate jdbc;

    public JdbcKeyRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(JdbcProductCopyRepository.MODULE);
    }

    @Override
    public Map<String, Integer> countByStatus(UUID productId) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("select status, count(*) as n from inventory.key where product_id = ? group by status",
                rs -> {
                    counts.put(rs.getString("status"), rs.getInt("n"));
                }, productId);
        return counts;
    }

    @Override
    public List<UUID> reserveFree(UUID productId, int quantity, UUID orderId, UUID reservationId) {
        return jdbc.query("with picked as materialized (select id from inventory.key where product_id = ? and status = 'free' "
                + "order by id limit ? for update skip locked) "
                + "update inventory.key k set status = 'reserved', order_id = ?, reservation_id = ? from picked "
                + "where k.id = picked.id returning k.id", (rs, row) -> rs.getObject("id", UUID.class), productId, quantity, orderId, reservationId);
    }

    @Override
    public int releaseByReservation(UUID reservationId) {
        return jdbc.update("update inventory.key set status = 'free', order_id = null, reservation_id = null "
                + "where reservation_id = ? and status = 'reserved'", reservationId);
    }

    @Override
    public int issueByReservation(UUID reservationId, Instant at) {
        return jdbc.update("update inventory.key set status = 'issued', issued_at = ? where reservation_id = ? and status = 'reserved'",
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC), reservationId);
    }

    @Override
    public List<StoredKey> byOrder(UUID orderId) {
        return jdbc.query("select id, product_id, status, dek_id, nonce, ciphertext from inventory.key where order_id = ? "
                + "order by issued_at nulls last, id", (rs, row) -> new StoredKey(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getString("status"), rs.getInt("dek_id"), rs.getBytes("nonce"), rs.getBytes("ciphertext")), orderId);
    }

    @Override
    public Set<String> insertNew(UUID productId, List<NewKey> keys) {
        Set<String> inserted = new HashSet<>();
        for (int from = 0; from < keys.size(); from += CHUNK) {
            List<NewKey> chunk = keys.subList(from, Math.min(from + CHUNK, keys.size()));
            StringBuilder sql = new StringBuilder("insert into inventory.key (id, product_id, dek_id, nonce, ciphertext, hmac) values ");
            List<Object> args = new ArrayList<>(chunk.size() * 6);
            for (int i = 0; i < chunk.size(); i++) {
                NewKey key = chunk.get(i);
                sql.append(i == 0 ? "(?, ?, ?, ?, ?, ?)" : ", (?, ?, ?, ?, ?, ?)");
                args.add(key.id());
                args.add(productId);
                args.add(key.dekId());
                args.add(key.nonce());
                args.add(key.ciphertext());
                args.add(key.hmac());
            }
            sql.append(" on conflict (product_id, hmac) do nothing returning hmac");
            jdbc.query(sql.toString(), rs -> {
                inserted.add(KeyHasher.hex(rs.getBytes("hmac")));
            }, args.toArray());
        }
        return inserted;
    }
}
