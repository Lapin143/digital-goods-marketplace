package dgm.inventory.inventory.stock.repository;

import dgm.inventory.inventory.registry.JdbcProductCopyRepository;
import dgm.inventory.inventory.stock.domain.ApiStock;
import dgm.kit.boot.ModuleDatabases;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Остаток по API в PostgreSQL под ролью {@code app_inventory} (права на таблицу выдаёт миграция R2, V5). */
@Component
public final class JdbcApiStockRepository implements ApiStockRepository {

    private static final String COLUMNS = "product_id, quantity, version, updated_at";
    private static final RowMapper<ApiStock> MAPPER = (rs, row) -> new ApiStock(rs.getObject("product_id", UUID.class), rs.getInt("quantity"),
            rs.getInt("version"), rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public JdbcApiStockRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(JdbcProductCopyRepository.MODULE);
    }

    @Override
    public boolean ensure(UUID productId, Instant at) {
        return jdbc.update("insert into inventory.api_stock (product_id, updated_at) values (?, ?) on conflict (product_id) do nothing",
                productId, time(at)) == 1;
    }

    @Override
    public Optional<ApiStock> find(UUID productId) {
        return jdbc.query("select " + COLUMNS + " from inventory.api_stock where product_id = ?", MAPPER, productId).stream().findFirst();
    }

    @Override
    public Optional<ApiStock> take(UUID productId, int n) {
        return jdbc.query("update inventory.api_stock set quantity = quantity - ? where product_id = ? and quantity >= ? returning " + COLUMNS,
                MAPPER, n, productId, n).stream().findFirst();
    }

    @Override
    public ApiStock give(UUID productId, int n) {
        return jdbc.query("update inventory.api_stock set quantity = quantity + ? where product_id = ? returning " + COLUMNS, MAPPER, n, productId)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Строки остатка по API нет: количество вернуть не удалось"));
    }

    @Override
    public Optional<ApiStock> replace(UUID productId, int quantity, int expectedVersion) {
        return jdbc.query("update inventory.api_stock set quantity = ? where product_id = ? and version = ? returning " + COLUMNS,
                MAPPER, quantity, productId, expectedVersion).stream().findFirst();
    }

    @Override
    public int activeUnits(UUID productId) {
        return units(productId, "active");
    }

    @Override
    public int usedUnits(UUID productId) {
        return units(productId, "used");
    }

    private int units(UUID productId, String status) {
        Integer sum = jdbc.queryForObject("select coalesce(sum(quantity), 0) from inventory.reservation where product_id = ? and status = ?",
                Integer.class, productId, status);
        return sum == null ? 0 : sum;
    }

    private static OffsetDateTime time(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
