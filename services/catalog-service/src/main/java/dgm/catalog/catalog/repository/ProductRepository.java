package dgm.catalog.catalog.repository;

import dgm.catalog.catalog.domain.ProductCard;
import dgm.catalog.catalog.domain.StorefrontFilter;
import dgm.catalog.catalog.domain.StorefrontItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Чтение товаров модуля «каталог»: схема {@code catalog}, роль {@code app_catalog}. */
public interface ProductRepository {

    /** Опубликованные товары по условиям, новые выше. Возвращает не больше {@code filter.limit() + 1} строк: лишняя нужна, чтобы узнать, есть ли следующая страница. */
    List<StorefrontItem> findPublished(StorefrontFilter filter);

    /** Карточка товара в любом статусе. */
    Optional<ProductCard> findCard(UUID id);
}
