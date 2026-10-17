package dgm.catalog.catalog.seller.repository;

import dgm.catalog.catalog.domain.BlockReason;
import dgm.catalog.catalog.domain.IssuanceMethod;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductForm;
import dgm.catalog.catalog.domain.ProductPage;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.domain.ProductType;
import dgm.kit.boot.ModuleDatabases;
import java.sql.PreparedStatement;
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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Товары продавца в PostgreSQL под ролью {@code app_catalog}. Выборка списка повторяет индекс {@code ix_product_seller_id}. */
@Component
public final class JdbcSellerProductRepository implements SellerProductRepository {

    /** Имя модуля базы в {@code dgm.db.modules} (application.yml): своя роль, свой пул, своя транзакция. */
    public static final String MODULE = "catalog";

    static final String COLUMNS = "id, seller_id, title, description, product_type, platform, activation_regions, price, issuance_method, "
            + "rejection_reason, status_before_block, block_reason, block_note, submitted_at, review_deadline_at, overdue_marked_at, "
            + "published_at, status, version, created_at, updated_at";

    private static final RowMapper<Product> PRODUCT = JdbcSellerProductRepository::product;

    private final JdbcTemplate jdbc;

    public JdbcSellerProductRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public Optional<Product> find(UUID sellerId, UUID productId) {
        return jdbc.query("select " + COLUMNS + " from catalog.product where id = ? and seller_id = ?", PRODUCT, productId, sellerId)
                .stream().findFirst();
    }

    @Override
    public Optional<Product> lock(UUID sellerId, UUID productId) {
        return jdbc.query("select " + COLUMNS + " from catalog.product where id = ? and seller_id = ? for update", PRODUCT, productId, sellerId)
                .stream().findFirst();
    }

    @Override
    public void insert(Product p) {
        jdbc.update("insert into catalog.product (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> bind(ps, p, 1));
    }

    @Override
    public boolean update(Product p, int expectedVersion) {
        int rows = jdbc.update("update catalog.product set title = ?, description = ?, product_type = ?, platform = ?, activation_regions = ?, "
                        + "price = ?, issuance_method = ?, rejection_reason = ?, status_before_block = ?, block_reason = ?, block_note = ?, "
                        + "submitted_at = ?, review_deadline_at = ?, overdue_marked_at = ?, published_at = ?, status = ?, version = ?, "
                        + "updated_at = ? where id = ? and seller_id = ? and version = ?",
                ps -> {
                    ProductForm f = p.form();
                    ps.setString(1, f.title());
                    ps.setString(2, f.description());
                    ps.setString(3, f.productType().code());
                    ps.setString(4, f.platform());
                    ps.setArray(5, ps.getConnection().createArrayOf("text", f.activationRegions().toArray()));
                    ps.setLong(6, f.price());
                    ps.setString(7, f.issuanceMethod().code());
                    ps.setString(8, p.rejectionReason());
                    ps.setString(9, p.statusBeforeBlock() == null ? null : p.statusBeforeBlock().code());
                    ps.setString(10, p.blockReason() == null ? null : p.blockReason().code());
                    ps.setString(11, p.blockNote());
                    ps.setObject(12, time(p.submittedAt()));
                    ps.setObject(13, time(p.reviewDeadlineAt()));
                    ps.setObject(14, time(p.overdueMarkedAt()));
                    ps.setObject(15, time(p.publishedAt()));
                    ps.setString(16, p.status().code());
                    ps.setInt(17, p.version());
                    ps.setObject(18, time(p.updatedAt()));
                    ps.setObject(19, p.id());
                    ps.setObject(20, p.sellerId());
                    ps.setInt(21, expectedVersion);
                });
        return rows == 1;
    }

    @Override
    public List<Product> list(UUID sellerId, List<ProductStatus> statuses, Optional<ProductPage.After> after, int limit) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from catalog.product where seller_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(sellerId);
        if (!statuses.isEmpty()) {
            sql.append(" and status in (").append(String.join(", ", Collections.nCopies(statuses.size(), "?"))).append(')');
            statuses.forEach(s -> args.add(s.code()));
        }
        after.ifPresent(key -> {
            sql.append(" and (created_at, id) < (?, ?)");
            args.add(time(key.createdAt()));
            args.add(key.id());
        });
        sql.append(" order by created_at desc, id desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), PRODUCT, args.toArray());
    }

    /** Значения в порядке {@link #COLUMNS}, начиная с номера {@code from}. */
    private static void bind(PreparedStatement ps, Product p, int from) throws SQLException {
        ProductForm f = p.form();
        int i = from;
        ps.setObject(i++, p.id());
        ps.setObject(i++, p.sellerId());
        ps.setString(i++, f.title());
        ps.setString(i++, f.description());
        ps.setString(i++, f.productType().code());
        ps.setString(i++, f.platform());
        ps.setArray(i++, ps.getConnection().createArrayOf("text", f.activationRegions().toArray()));
        ps.setLong(i++, f.price());
        ps.setString(i++, f.issuanceMethod().code());
        ps.setString(i++, p.rejectionReason());
        ps.setString(i++, p.statusBeforeBlock() == null ? null : p.statusBeforeBlock().code());
        ps.setString(i++, p.blockReason() == null ? null : p.blockReason().code());
        ps.setString(i++, p.blockNote());
        ps.setObject(i++, time(p.submittedAt()));
        ps.setObject(i++, time(p.reviewDeadlineAt()));
        ps.setObject(i++, time(p.overdueMarkedAt()));
        ps.setObject(i++, time(p.publishedAt()));
        ps.setString(i++, p.status().code());
        ps.setInt(i++, p.version());
        ps.setObject(i++, time(p.createdAt()));
        ps.setObject(i, time(p.updatedAt()));
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Product product(ResultSet rs, int row) throws SQLException {
        String[] regions = (String[]) rs.getArray("activation_regions").getArray();
        ProductForm form = new ProductForm(rs.getString("title"), rs.getString("description"),
                ProductType.fromCode(rs.getString("product_type")), rs.getString("platform"), List.of(regions), rs.getLong("price"),
                IssuanceMethod.fromCode(rs.getString("issuance_method")));
        String beforeBlock = rs.getString("status_before_block");
        String blockReason = rs.getString("block_reason");
        return new Product(rs.getObject("id", UUID.class), rs.getObject("seller_id", UUID.class), form, rs.getString("rejection_reason"),
                beforeBlock == null ? null : ProductStatus.fromCode(beforeBlock), blockReason == null ? null : BlockReason.fromCode(blockReason),
                rs.getString("block_note"), instant(rs, "submitted_at"), instant(rs, "review_deadline_at"), instant(rs, "overdue_marked_at"),
                instant(rs, "published_at"), ProductStatus.fromCode(rs.getString("status")), rs.getInt("version"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
