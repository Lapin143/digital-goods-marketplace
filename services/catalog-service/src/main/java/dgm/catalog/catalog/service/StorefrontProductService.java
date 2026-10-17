package dgm.catalog.catalog.service;

import dgm.catalog.catalog.cache.ProductCache;
import dgm.catalog.catalog.domain.StorefrontCard;
import dgm.catalog.catalog.repository.ProductRepository;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Карточка товара на витрине ({@code getStorefrontProduct}, OP-28, US-3.9). Порядок чтения: кэш, затем база; найденная в базе карточка
 * кладётся в кэш. Товар в любом статусе кроме «опубликован» это 404 «Товар не найден», как и несуществующий: статус наружу не раскрывается.
 *
 * <p>Читается готовое тело ответа: кэш хранит то, что получил бы клиент, поэтому попадание в кэш не требует ни базы, ни разбора.
 */
@Component
public final class StorefrontProductService {

    private final ProductRepository products;
    private final ProductCache cache;

    public StorefrontProductService(ProductRepository products, ProductCache cache) {
        this.products = products;
        this.cache = cache;
    }

    /**
     * @param render превращает карточку в тело ответа; вызывается только при промахе кэша
     * @return тело ответа
     * @throws ProblemException 404, если товара нет или он не опубликован
     */
    public String body(UUID productId, Function<StorefrontCard, String> render) {
        Optional<String> cached = cache.get(productId);
        if (cached.isPresent()) {
            return cached.get();
        }
        StorefrontCard card = products.findPublishedCard(productId)
                .orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Товар не найден."));
        String body = render.apply(card);
        cache.put(productId, body);
        return body;
    }
}
