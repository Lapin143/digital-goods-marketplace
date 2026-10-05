package dgm.catalog.catalog;

import dgm.catalog.catalog.domain.ProductCard;
import dgm.catalog.catalog.domain.StorefrontFilter;
import dgm.catalog.catalog.domain.StorefrontItem;
import dgm.catalog.catalog.repository.ProductRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий в памяти для модульных тестов: порядок «новые выше, при равенстве больший идентификатор выше» и позиция курсора
 * работают как в SQL, поэтому постраничный обход проверяется целиком без базы. Последнее условие выборки запоминается.
 */
public final class FakeProducts implements ProductRepository {

    private static final Comparator<StorefrontItem> NEWEST_FIRST = Comparator.comparing(StorefrontItem::publishedAt)
            .thenComparing(StorefrontItem::id).reversed();

    private final List<StorefrontItem> items = new ArrayList<>();
    private final Map<UUID, ProductCard> cards = new HashMap<>();
    private StorefrontFilter lastFilter;

    public static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", n));
    }

    public static StorefrontItem item(int n, Instant publishedAt) {
        return new StorefrontItem(id(n), "Товар " + n, "game_key", "Steam", 100_00L * n, "RUB", true, publishedAt);
    }

    public FakeProducts with(StorefrontItem... more) {
        items.addAll(List.of(more));
        return this;
    }

    public FakeProducts withCard(ProductCard card) {
        cards.put(card.id(), card);
        return this;
    }

    public StorefrontFilter lastFilter() {
        return lastFilter;
    }

    @Override
    public List<StorefrontItem> findPublished(StorefrontFilter filter) {
        lastFilter = filter;
        return items.stream()
                .filter(i -> filter.titleContains().map(t -> i.title().toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT))).orElse(true))
                .filter(i -> filter.priceFrom().map(p -> i.price() >= p).orElse(true))
                .filter(i -> filter.priceTo().map(p -> i.price() <= p).orElse(true))
                .sorted(NEWEST_FIRST)
                .filter(i -> filter.after().map(a -> before(i, a)).orElse(true))
                .limit(filter.limit() + 1L)
                .toList();
    }

    private static boolean before(StorefrontItem item, StorefrontFilter.After after) {
        int byTime = item.publishedAt().compareTo(after.publishedAt());
        return byTime < 0 || (byTime == 0 && item.id().compareTo(after.id()) < 0);
    }

    @Override
    public Optional<ProductCard> findCard(UUID id) {
        return Optional.ofNullable(cards.get(id));
    }
}
