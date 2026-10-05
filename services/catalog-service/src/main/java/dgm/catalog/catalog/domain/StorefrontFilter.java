package dgm.catalog.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Условия выборки витрины (US-3.9 – US-3.11). Поля, которых нет в запросе, пусты и в выборку не входят.
 *
 * @param titleContains часть названия без учёта регистра (текст уже без управляющих знаков шаблона)
 * @param productTypes  типы товара, подходит любой из них
 * @param platform      платформа
 * @param region        регион активации: подходят товары с этим регионом и товары без ограничений
 * @param priceFrom     нижняя граница цены в копейках, включительно
 * @param priceTo       верхняя граница цены в копейках, включительно
 * @param limit         размер страницы
 * @param after         позиция курсора: последний показанный товар
 */
public record StorefrontFilter(Optional<String> titleContains, Optional<List<String>> productTypes, Optional<String> platform,
                               Optional<String> region, Optional<Long> priceFrom, Optional<Long> priceTo, int limit,
                               Optional<After> after) {

    /** Позиция в порядке «новые публикации выше». */
    public record After(Instant publishedAt, UUID id) {
    }
}
