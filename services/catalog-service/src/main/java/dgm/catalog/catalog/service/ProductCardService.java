package dgm.catalog.catalog.service;

import dgm.catalog.catalog.domain.IssuanceMethod;
import dgm.catalog.catalog.domain.ProductCard;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.repository.ProductRepository;
import dgm.catalog.sellers.api.SellerIntegrations;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Карточка товара для сервиса заказов (OP-50, SEQ-01, шаги 4 и 5). У опубликованного товара с выдачей по API (R2) без действующего
 * подключения системы продавца статус отдаётся как {@code blocked}: платформа не может запросить у продавца ключ, и заказ не оформляется.
 * Снять такой товар с витрины блокировкой (событие {@code product.blocked}) придётся вместе с блокировкой продавца (срез 3), см. находку F5-16.
 */
@Component
public final class ProductCardService {

    private final ProductRepository products;
    private final SellerIntegrations integrations;

    public ProductCardService(ProductRepository products, SellerIntegrations integrations) {
        this.products = products;
        this.integrations = integrations;
    }

    /** Карточка товара в любом статусе; товара нет: 404. Доступность товара решает вызывающий по полю статуса. */
    public ProductCard get(UUID id) {
        ProductCard card = products.findCard(id).orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Товар не найден."));
        boolean needsIntegration = IssuanceMethod.SELLER_API.code().equals(card.issuanceMethod())
                && ProductStatus.PUBLISHED.code().equals(card.status());
        if (needsIntegration && !integrations.hasActive(card.sellerId())) {
            return new ProductCard(card.id(), card.sellerId(), card.title(), ProductStatus.BLOCKED.code(), card.price(), card.currency(),
                    card.issuanceMethod(), card.productType());
        }
        return card;
    }
}
