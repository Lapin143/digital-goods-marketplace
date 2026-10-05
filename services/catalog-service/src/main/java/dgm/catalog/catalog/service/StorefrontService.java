package dgm.catalog.catalog.service;

import dgm.catalog.catalog.domain.StorefrontFilter;
import dgm.catalog.catalog.domain.StorefrontItem;
import dgm.catalog.catalog.domain.StorefrontPage;
import dgm.catalog.catalog.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Витрина: страница опубликованных товаров по условиям (OP-28, US-3.9 – US-3.11). */
@Component
public final class StorefrontService {

    private final ProductRepository products;

    public StorefrontService(ProductRepository products) {
        this.products = products;
    }

    public StorefrontPage list(StorefrontFilter filter) {
        List<StorefrontItem> rows = products.findPublished(filter);
        boolean more = rows.size() > filter.limit();
        List<StorefrontItem> items = more ? rows.subList(0, filter.limit()) : rows;
        Optional<StorefrontFilter.After> next = more
                ? Optional.of(new StorefrontFilter.After(items.get(items.size() - 1).publishedAt(), items.get(items.size() - 1).id()))
                : Optional.empty();
        return new StorefrontPage(List.copyOf(items), filter.limit(), next);
    }
}
