package dgm.inventory.inventory.registry;

import dgm.kit.boot.ModuleDatabases;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Копии товаров в PostgreSQL под ролью {@code app_inventory}. */
@Component
public final class JdbcProductCopyRepository implements ProductCopyRepository {

    /** Имя модуля базы в {@code dgm.db.modules} (application.yml): своя роль, свой пул, своя транзакция. */
    public static final String MODULE = "inventory";

    private static final String COLUMNS = "product_id, seller_id, issuance_method, status, source_version";
    private static final RowMapper<ProductCopy> COPY = (rs, row) -> new ProductCopy(rs.getObject("product_id", UUID.class),
            rs.getObject("seller_id", UUID.class), rs.getString("issuance_method"), rs.getString("status"), rs.getInt("source_version"));

    private final JdbcTemplate jdbc;

    public JdbcProductCopyRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public Optional<ProductCopy> find(UUID productId) {
        return jdbc.query("select " + COLUMNS + " from inventory.product_copy where product_id = ?", COPY, productId).stream().findFirst();
    }

    @Override
    public Optional<ProductCopy> lock(UUID productId) {
        return jdbc.query("select " + COLUMNS + " from inventory.product_copy where product_id = ? for update", COPY, productId).stream()
                .findFirst();
    }

    @Override
    public boolean apply(ProductCopy copy, Instant at) {
        return jdbc.update("insert into inventory.product_copy (product_id, seller_id, issuance_method, status, source_version, updated_at) "
                + "values (?, ?, ?, ?, ?, ?) on conflict (product_id) do update set seller_id = excluded.seller_id, "
                + "issuance_method = excluded.issuance_method, status = excluded.status, source_version = excluded.source_version, "
                + "updated_at = excluded.updated_at where inventory.product_copy.source_version < excluded.source_version",
                copy.productId(), copy.sellerId(), copy.issuanceMethod(), copy.status(), copy.sourceVersion(), time(at)) == 1;
    }

    @Override
    public boolean setStatus(UUID productId, String status, Instant at) {
        return jdbc.update("update inventory.product_copy set status = ?, updated_at = ? where product_id = ?", status, time(at), productId) == 1;
    }

    private static OffsetDateTime time(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
