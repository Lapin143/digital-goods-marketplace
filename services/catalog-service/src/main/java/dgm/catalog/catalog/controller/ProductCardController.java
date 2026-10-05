package dgm.catalog.catalog.controller;

import dgm.catalog.catalog.domain.ProductCard;
import dgm.catalog.catalog.service.ProductCardService;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** {@code getProductCard}: внутренний вызов {@code order-service}, mTLS, вызывающего проверяет {@code CallerFilter} (OP-50). */
@RestController
public class ProductCardController {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ProductCardService service;

    public ProductCardController(ProductCardService service) {
        this.service = service;
    }

    @GetMapping("/internal/v1/products/{productId}")
    public ResponseEntity<String> get(@PathVariable("productId") String productId) {
        if (!ID.matcher(productId).matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Идентификатор товара должен быть UUID в нижнем регистре.");
        }
        return ResponseEntity.ok().contentType(StorefrontController.JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Json.write(toJson(service.get(UUID.fromString(productId)))));
    }

    static Map<String, Object> toJson(ProductCard card) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", card.id().toString());
        body.put("sellerId", card.sellerId().toString());
        body.put("title", card.title());
        body.put("status", card.status());
        body.put("price", Views.money(card.price(), card.currency()));
        body.put("issuanceMethod", card.issuanceMethod());
        body.put("productType", card.productType());
        return body;
    }
}
