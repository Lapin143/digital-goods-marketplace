package dgm.catalog.catalog.repository;

import dgm.kit.boot.ModuleDatabases;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Копия остатка в PostgreSQL под ролью {@code app_catalog}. Внутри транзакции потребителя события: соединение даёт каркас. */
@Component
public final class JdbcStockViewRepository implements StockViewRepository {

    private final JdbcTemplate jdbc;

    public JdbcStockViewRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc("catalog");
    }

    @Override
    public Outcome apply(UUID productId, int available, Instant eventTime) {
        OffsetDateTime time = OffsetDateTime.ofInstant(eventTime, ZoneOffset.UTC);
        int rows = jdbc.update("insert into catalog.stock_view (product_id, available, last_event_at, updated_at) "
                + "select p.id, ?, ?, now() from catalog.product p where p.id = ? "
                + "on conflict (product_id) do update set available = excluded.available, last_event_at = excluded.last_event_at, "
                + "updated_at = excluded.updated_at where catalog.stock_view.last_event_at <= excluded.last_event_at",
                available, time, productId);
        if (rows == 1) {
            return Outcome.APPLIED;
        }
        Integer known = jdbc.queryForObject("select count(*) from catalog.product where id = ?", Integer.class, productId);
        return known != null && known > 0 ? Outcome.STALE : Outcome.UNKNOWN_PRODUCT;
    }
}
