package dgm.order.orders.client;

import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.client.PeerClient;
import dgm.kit.client.PeerRequest;
import dgm.kit.json.JsonException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link CatalogClient} поверх {@code PeerClient}: {@code GET /internal/v1/products/{productId}} по mTLS. Карточка читается строго, лишние поля игнорируются. */
public final class PeerCatalogClient implements CatalogClient {

    /** Имя соседа в {@code dgm.peers.*}. */
    public static final String PEER = "catalog-service";

    private static final Logger LOG = LoggerFactory.getLogger(PeerCatalogClient.class);

    private final Supplier<PeerClient> peer;

    public PeerCatalogClient(Supplier<PeerClient> peer) {
        this.peer = peer;
    }

    @Override
    public Optional<ProductCard> card(UUID productId) {
        try {
            return Optional.of(parse(peer.get().call(PeerRequest.get("/internal/v1/products/" + productId)).json(), productId));
        } catch (DependencyRejectedException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            LOG.error("Каталог отклонил запрос карточки товара {}: код {}", productId, e.status());
            throw new DependencyUnavailableException(PEER, DependencyUnavailableException.SERVER_ERROR, e.status(), e);
        } catch (JsonException | ClassCastException | NullPointerException | IllegalArgumentException e) {
            LOG.error("Ответ каталога о товаре {} не разобран", productId);
            throw new DependencyUnavailableException(PEER, DependencyUnavailableException.SERVER_ERROR, 200, e);
        }
    }

    private static ProductCard parse(Map<String, Object> json, UUID requested) {
        Map<?, ?> price = (Map<?, ?>) json.get("price");
        UUID productId = UUID.fromString((String) json.get("productId"));
        if (!productId.equals(requested)) {
            throw new IllegalArgumentException("Каталог ответил о другом товаре");
        }
        return new ProductCard(productId, UUID.fromString((String) json.get("sellerId")), required((String) json.get("title")),
                required((String) json.get("status")), ((Number) price.get("amount")).longValue(), required((String) price.get("currency")),
                required((String) json.get("issuanceMethod")));
    }

    /** Поле карточки, без которого заказ не оформить: пустое значение это ответ не по формату. */
    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("В карточке товара нет обязательного поля");
        }
        return value;
    }
}
