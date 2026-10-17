package dgm.catalog.catalog.seller;

import dgm.catalog.catalog.domain.ModerationQueueItem;
import dgm.catalog.catalog.domain.ModerationQueuePage;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductPage;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.seller.repository.SellerProductRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий товаров продавца в памяти для модульных тестов. Порядок «новые выше, при равенстве больший идентификатор выше», позиция
 * курсора, ограничение запросов профилем продавца и проверка версии при записи работают как в SQL.
 */
public class FakeSellerProducts implements SellerProductRepository {

    private static final Comparator<Product> NEWEST_FIRST = Comparator.comparing(Product::createdAt).thenComparing(Product::id).reversed();

    private final Map<UUID, Product> rows = new LinkedHashMap<>();
    private int updates;

    public FakeSellerProducts with(Product... products) {
        for (Product product : products) {
            rows.put(product.id(), product);
        }
        return this;
    }

    public Product row(UUID id) {
        return rows.get(id);
    }

    public int count() {
        return rows.size();
    }

    /** Сколько раз запись прошла (версия совпала). */
    public int updates() {
        return updates;
    }

    @Override
    public Optional<Product> findById(UUID productId) {
        return Optional.ofNullable(rows.get(productId));
    }

    @Override
    public Optional<Product> lockById(UUID productId) {
        return findById(productId);
    }

    @Override
    public List<ModerationQueueItem> moderationQueue(Optional<ModerationQueuePage.After> after, int limit) {
        Comparator<Product> oldestFirst = Comparator.comparing(Product::submittedAt).thenComparing(Product::id);
        return rows.values().stream()
                .filter(p -> p.status() == ProductStatus.ON_MODERATION)
                .sorted(oldestFirst)
                .filter(p -> after.map(a -> {
                    int byTime = p.submittedAt().compareTo(a.submittedAt());
                    return byTime > 0 || (byTime == 0 && p.id().compareTo(a.id()) > 0);
                }).orElse(true))
                .limit(limit)
                .map(p -> new ModerationQueueItem(p.id(), p.sellerId(), p.form().title(), p.form().productType().code(), p.form().price(), "RUB",
                        p.submittedAt(), p.reviewDeadlineAt()))
                .toList();
    }

    @Override
    public Optional<Product> find(UUID sellerId, UUID productId) {
        return Optional.ofNullable(rows.get(productId)).filter(p -> p.sellerId().equals(sellerId));
    }

    @Override
    public Optional<Product> lock(UUID sellerId, UUID productId) {
        return find(sellerId, productId);
    }

    @Override
    public void insert(Product product) {
        if (rows.putIfAbsent(product.id(), product) != null) {
            throw new IllegalStateException("Товар уже есть: " + product.id());
        }
    }

    @Override
    public boolean update(Product product, int expectedVersion) {
        Product stored = rows.get(product.id());
        if (stored == null || stored.version() != expectedVersion) {
            return false;
        }
        rows.put(product.id(), product);
        updates++;
        return true;
    }

    @Override
    public List<Product> list(UUID sellerId, List<ProductStatus> statuses, Optional<ProductPage.After> after, int limit) {
        List<Product> found = new ArrayList<>(rows.values());
        return found.stream()
                .filter(p -> p.sellerId().equals(sellerId))
                .filter(p -> statuses.isEmpty() || statuses.contains(p.status()))
                .sorted(NEWEST_FIRST)
                .filter(p -> after.map(a -> before(p, a)).orElse(true))
                .limit(limit)
                .toList();
    }

    private static boolean before(Product product, ProductPage.After after) {
        int byTime = product.createdAt().compareTo(after.createdAt());
        return byTime < 0 || (byTime == 0 && product.id().compareTo(after.id()) < 0);
    }
}
