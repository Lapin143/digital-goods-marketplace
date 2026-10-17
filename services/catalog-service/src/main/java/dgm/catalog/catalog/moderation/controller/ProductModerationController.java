package dgm.catalog.catalog.moderation.controller;

import dgm.catalog.catalog.controller.StorefrontController;
import dgm.catalog.catalog.domain.ModerationQueuePage;
import dgm.catalog.catalog.domain.Moderator;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.moderation.service.ProductModerationService;
import dgm.catalog.catalog.seller.controller.SellerProductViews;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.CurrentUser;
import dgm.kit.web.ClientAddress;
import dgm.kit.web.QueryParams;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Модерация товаров: {@code listProductModerationQueue}, {@code getProductForModeration}, {@code approveProduct}, {@code rejectProduct},
 * {@code blockProduct} (OP-22 – OP-24). Роль модератора, область {@code staff.moderation} и второй фактор проверяет фильтр каркаса по
 * правилам маршрутов, ключ идемпотентности ведёт охранник каркаса.
 */
@RestController
public class ProductModerationController {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String BASE = "/api/v1/staff/products";

    private final ProductModerationService service;

    public ProductModerationController(ProductModerationService service) {
        this.service = service;
    }

    @GetMapping(BASE)
    public ResponseEntity<String> queue(HttpServletRequest request) {
        QueryParams params = QueryParams.of(request);
        int limit = params.limit();
        Optional<ModerationQueuePage.After> after = cursor(params);
        params.check();
        return noStore(Json.write(ModerationQueueViews.queue(service.queue(limit, after))), null);
    }

    @GetMapping(BASE + "/{productId}")
    public ResponseEntity<String> card(@PathVariable("productId") String productId) {
        return decided(service.card(productId(productId)));
    }

    @PostMapping(BASE + "/{productId}/approve")
    public ResponseEntity<String> approve(@PathVariable("productId") String productId, HttpServletRequest request) {
        return decided(service.approve(moderator(request), productId(productId)));
    }

    @PostMapping(BASE + "/{productId}/reject")
    public ResponseEntity<String> reject(@PathVariable("productId") String productId, HttpServletRequest request) throws IOException {
        UUID id = productId(productId);
        String reason = ReasonBodies.reason(request);
        return decided(service.reject(moderator(request), id, reason));
    }

    @PostMapping(BASE + "/{productId}/block")
    public ResponseEntity<String> block(@PathVariable("productId") String productId, HttpServletRequest request) throws IOException {
        UUID id = productId(productId);
        String reason = ReasonBodies.reason(request);
        return decided(service.block(moderator(request), id, reason));
    }

    private static ResponseEntity<String> decided(Product product) {
        return noStore(Json.write(SellerProductViews.product(product)), SellerProductViews.etag(product));
    }

    private static ResponseEntity<String> noStore(String body, String etag) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().contentType(StorefrontController.JSON).header(HttpHeaders.CACHE_CONTROL, "no-store");
        return (etag == null ? builder : builder.header(HttpHeaders.ETAG, etag)).body(body);
    }

    private static Moderator moderator(HttpServletRequest request) {
        return new Moderator(CurrentUser.id(CurrentUser.of(request)), ClientAddress.of(request));
    }

    private static UUID productId(String value) {
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Идентификатор товара должен быть UUID в нижнем регистре.");
        }
        return UUID.fromString(value);
    }

    private static Optional<ModerationQueuePage.After> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("p", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ModerationQueuePage.After(Instant.parse(values.get().get("p")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }
}
