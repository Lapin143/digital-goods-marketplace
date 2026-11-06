package dgm.inventory.inventory.stock.controller;

import dgm.inventory.inventory.stock.domain.ApiStock;
import dgm.inventory.inventory.stock.service.ApiStockService;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import dgm.kit.web.IfMatch;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code setApiStock} (OP-98): остаток своего товара с выдачей по API (FT-4.3, US-4.7, ADR-008). Роль продавца, область
 * {@code seller.catalog}, второй фактор, вход по API-ключу и ключ идемпотентности проверяют фильтры каркаса и шлюз по правилам маршрутов. Профиль
 * продавца берётся из личности (токена или ключа), а не из запроса. Версия записи это {@code ETag} ответа и {@code If-Match} запроса.
 */
@RestController
public class ApiStockController {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ApiStockService service;

    public ApiStockController(ApiStockService service) {
        this.service = service;
    }

    @PutMapping("/api/v1/seller/products/{productId}/api-stock")
    public ResponseEntity<String> set(@PathVariable("productId") String productId, HttpServletRequest request) throws IOException {
        UUID seller = CurrentUser.sellerId(CurrentUser.of(request));
        UUID product = productId(productId);
        long expected = IfMatch.version(request);
        int quantity = ApiStockBodies.quantity(request);
        if (expected > Integer.MAX_VALUE) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Версия в If-Match слишком велика.");
        }
        ApiStock saved = service.set(seller, product, (int) expected, quantity);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", saved.productId().toString());
        body.put("quantity", saved.quantity());
        body.put("version", saved.version());
        body.put("updatedAt", Timestamps.format(saved.updatedAt()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, IfMatch.etag(saved.version())).body(Json.write(body));
    }

    private static UUID productId(String value) {
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Идентификатор товара должен быть UUID в нижнем регистре.");
        }
        return UUID.fromString(value);
    }
}
