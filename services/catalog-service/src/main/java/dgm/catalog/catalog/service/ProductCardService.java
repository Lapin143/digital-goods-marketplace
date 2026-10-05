package dgm.catalog.catalog.service;

import dgm.catalog.catalog.domain.ProductCard;
import dgm.catalog.catalog.repository.ProductRepository;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Карточка товара для сервиса заказов (OP-50, SEQ-01, шаги 4 и 5). */
@Component
public final class ProductCardService {

    private final ProductRepository products;

    public ProductCardService(ProductRepository products) {
        this.products = products;
    }

    /** Карточка товара в любом статусе; товара нет: 404. Доступность товара решает вызывающий по полю статуса. */
    public ProductCard get(UUID id) {
        return products.findCard(id).orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Товар не найден."));
    }
}
