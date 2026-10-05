package dgm.catalog.catalog.service;

import static dgm.catalog.catalog.FakeProducts.id;
import static dgm.catalog.catalog.FakeProducts.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.catalog.FakeProducts;
import dgm.catalog.catalog.domain.StorefrontFilter;
import dgm.catalog.catalog.domain.StorefrontItem;
import dgm.catalog.catalog.domain.StorefrontPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Страницы витрины: лишняя строка показывает следующую страницу, курсор указывает на последний показанный товар. */
class StorefrontServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private static StorefrontFilter filter(int limit, StorefrontFilter.After after) {
        return new StorefrontFilter(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), limit, Optional.ofNullable(after));
    }

    private static FakeProducts five() {
        return new FakeProducts().with(item(1, T0), item(2, T0.plusSeconds(60)), item(3, T0.plusSeconds(120)), item(4, T0.plusSeconds(180)),
                item(5, T0.plusSeconds(240)));
    }

    private static List<UUID> walk(StorefrontService service, int limit) {
        List<UUID> seen = new ArrayList<>();
        StorefrontFilter.After after = null;
        int pages = 0;
        do {
            StorefrontPage page = service.list(filter(limit, after));
            page.items().forEach(i -> seen.add(i.id()));
            after = page.next().orElse(null);
            pages++;
        } while (after != null && pages < 10);
        return seen;
    }

    @Test
    void emptyShowcaseIsAnEmptyLastPage() {
        StorefrontPage page = new StorefrontService(new FakeProducts()).list(filter(20, null));
        assertTrue(page.items().isEmpty());
        assertTrue(page.next().isEmpty());
        assertEquals(20, page.limit());
    }

    @Test
    void fewerThanLimitIsTheLastPage() {
        StorefrontPage page = new StorefrontService(five()).list(filter(10, null));
        assertEquals(5, page.items().size());
        assertTrue(page.next().isEmpty());
    }

    @Test
    void exactlyLimitIsTheLastPage() {
        StorefrontPage page = new StorefrontService(five()).list(filter(5, null));
        assertEquals(5, page.items().size());
        assertTrue(page.next().isEmpty());
    }

    @Test
    void morePublishedThanLimitGivesCursorOfLastShownItem() {
        StorefrontPage page = new StorefrontService(five()).list(filter(2, null));
        assertEquals(List.of(id(5), id(4)), page.items().stream().map(StorefrontItem::id).toList());
        StorefrontFilter.After next = page.next().orElseThrow();
        assertEquals(id(4), next.id());
        assertEquals(T0.plusSeconds(180), next.publishedAt());
    }

    @Test
    void walkingAllPagesVisitsEveryItemOnceNewestFirst() {
        assertEquals(List.of(id(5), id(4), id(3), id(2), id(1)), walk(new StorefrontService(five()), 2));
    }

    @Test
    void itemsWithTheSamePublicationTimeAreNotLostOrRepeated() {
        FakeProducts same = new FakeProducts().with(item(1, T0), item(2, T0), item(3, T0), item(4, T0));
        assertEquals(List.of(id(4), id(3), id(2), id(1)), walk(new StorefrontService(same), 1));
    }

    @Test
    void filterReachesRepositoryUnchanged() {
        FakeProducts products = five();
        StorefrontFilter wanted = new StorefrontFilter(Optional.of("Товар"), Optional.of(List.of("game_key")), Optional.of("Steam"),
                Optional.of("RU"), Optional.of(100L), Optional.of(900L), 3, Optional.empty());
        new StorefrontService(products).list(wanted);
        assertEquals(wanted, products.lastFilter());
    }

    @Test
    void returnedItemsCannotBeChanged() {
        StorefrontPage page = new StorefrontService(five()).list(filter(2, null));
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    }
}
