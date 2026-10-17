package dgm.catalog.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Страница товаров продавца: новые выше и ключ следующей страницы. Ключ это пара «время создания, идентификатор»: порядок однозначен,
 * и повторное чтение с ключом не пропускает и не повторяет товары.
 */
public record ProductPage(List<Product> items, int limit, Optional<After> next) {

    /** Товары строго после этой пары в порядке «новые выше». */
    public record After(Instant createdAt, UUID id) {
    }
}
