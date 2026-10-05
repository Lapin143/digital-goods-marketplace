package dgm.catalog.catalog.repository;

import dgm.catalog.catalog.domain.ProductCard;
import dgm.catalog.catalog.domain.StorefrontFilter;
import dgm.catalog.catalog.domain.StorefrontItem;
import dgm.kit.boot.ModuleDatabases;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Товары в PostgreSQL. Запросы повторяют индексы из DDL: частичные по {@code status = 'published'} и триграммный по названию. */
@Component
public final class JdbcProductRepository implements ProductRepository {

    private final JdbcTemplate jdbc;

    public JdbcProductRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc("catalog");
    }

    @Override
    public List<StorefrontItem> findPublished(StorefrontFilter f) {
        StringBuilder sql = new StringBuilder("select p.id, p.title, p.product_type, p.platform, p.price, p.currency, "
                + "coalesce(s.in_stock, false) as in_stock, p.published_at "
                + "from catalog.product p left join catalog.stock_view s on s.product_id = p.id where p.status = 'published'");
        List<Object> args = new ArrayList<>();
        f.titleContains().ifPresent(text -> {
            sql.append(" and lower(p.title) like '%' || lower(?) || '%' escape '\\'");
            args.add(escapeLike(text));
        });
        f.productTypes().ifPresent(types -> {
            sql.append(" and p.product_type in (").append(String.join(", ", java.util.Collections.nCopies(types.size(), "?"))).append(')');
            args.addAll(types);
        });
        f.platform().ifPresent(platform -> {
            sql.append(" and p.platform = ?");
            args.add(platform);
        });
        f.region().ifPresent(region -> {
            sql.append(" and (cardinality(p.activation_regions) = 0 or ? = any(p.activation_regions))");
            args.add(region);
        });
        f.priceFrom().ifPresent(from -> {
            sql.append(" and p.price >= ?");
            args.add(from);
        });
        f.priceTo().ifPresent(to -> {
            sql.append(" and p.price <= ?");
            args.add(to);
        });
        f.after().ifPresent(after -> {
            sql.append(" and (p.published_at, p.id) < (?, ?)");
            args.add(OffsetDateTime.ofInstant(after.publishedAt(), ZoneOffset.UTC));
            args.add(after.id());
        });
        sql.append(" order by p.published_at desc, p.id desc limit ?");
        args.add(f.limit() + 1);
        return jdbc.query(sql.toString(), (rs, row) -> new StorefrontItem(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("product_type"),
                rs.getString("platform"),
                rs.getLong("price"),
                rs.getString("currency").trim(),
                rs.getBoolean("in_stock"),
                rs.getObject("published_at", OffsetDateTime.class).toInstant()), args.toArray());
    }

    @Override
    public Optional<ProductCard> findCard(UUID id) {
        List<ProductCard> found = jdbc.query("select id, seller_id, title, status, price, currency, issuance_method, product_type "
                + "from catalog.product where id = ?", (rs, row) -> new ProductCard(
                rs.getObject("id", UUID.class),
                rs.getObject("seller_id", UUID.class),
                rs.getString("title"),
                rs.getString("status"),
                rs.getLong("price"),
                rs.getString("currency").trim(),
                rs.getString("issuance_method"),
                rs.getString("product_type")), id);
        return found.stream().findFirst();
    }

    /** Знаки шаблона LIKE в тексте поиска теряют особый смысл: «50%» ищет именно «50%». */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
