package dgm.catalog.catalog.domain;

import java.util.List;
import java.util.Optional;

/** Страница витрины и позиция следующей (пусто на последней странице). */
public record StorefrontPage(List<StorefrontItem> items, int limit, Optional<StorefrontFilter.After> next) {
}
