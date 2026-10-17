package dgm.catalog.catalog.seller.controller;

import dgm.catalog.catalog.controller.StorefrontController;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductForm;
import dgm.catalog.catalog.domain.ProductPage;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.seller.service.SellerProductService;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.CurrentUser;
import dgm.kit.web.IfMatch;
import dgm.kit.web.QueryParams;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Товары продавца: {@code createSellerProduct}, {@code listSellerProducts}, {@code getSellerProduct}, {@code replaceSellerProduct},
 * {@code submitSellerProduct}, {@code archiveSellerProduct}, {@code restoreSellerProduct} (OP-20, OP-21, OP-25, OP-26). Роль продавца,
 * область {@code seller.catalog} и второй фактор проверяет фильтр каркаса по правилам маршрутов, ключ идемпотентности ведёт охранник
 * каркаса. Профиль продавца берётся из claim {@code seller_id} токена (Р-18), а не из запроса: чужой товар недоступен.
 */
@RestController
public class SellerProductController {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String BASE = "/api/v1/seller/products";
    private static final Set<String> STATUSES = Arrays.stream(ProductStatus.values()).map(ProductStatus::code).collect(Collectors.toUnmodifiableSet());

    private final SellerProductService service;

    public SellerProductController(SellerProductService service) {
        this.service = service;
    }

    @PostMapping(BASE)
    public ResponseEntity<String> create(HttpServletRequest request) throws IOException {
        UUID sellerId = sellerId(request);
        ProductForm form = ProductForms.read(request);
        Product product = service.create(sellerId, form);
        return ResponseEntity.status(HttpStatus.CREATED).contentType(StorefrontController.JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, SellerProductViews.etag(product)).header(HttpHeaders.LOCATION, BASE + "/" + product.id())
                .body(Json.write(SellerProductViews.product(product)));
    }

    @GetMapping(BASE)
    public ResponseEntity<String> list(HttpServletRequest request) {
        UUID sellerId = sellerId(request);
        QueryParams params = QueryParams.of(request, "status");
        int limit = params.limit();
        List<ProductStatus> statuses = params.list("status", STATUSES).orElse(List.of()).stream().map(ProductStatus::fromCode).toList();
        Optional<ProductPage.After> after = cursor(params);
        params.check();
        ProductPage page = service.list(sellerId, statuses, limit, after);
        return ResponseEntity.ok().contentType(StorefrontController.JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Json.write(SellerProductViews.page(page)));
    }

    @GetMapping(BASE + "/{productId}")
    public ResponseEntity<String> get(@PathVariable("productId") String productId, HttpServletRequest request) {
        return ok(service.get(sellerId(request), productId(productId)));
    }

    @PutMapping(BASE + "/{productId}")
    public ResponseEntity<String> replace(@PathVariable("productId") String productId, HttpServletRequest request) throws IOException {
        UUID sellerId = sellerId(request);
        UUID id = productId(productId);
        long expectedVersion = IfMatch.version(request);
        ProductForm form = ProductForms.read(request);
        return ok(service.replace(sellerId, id, expectedVersion, form));
    }

    @PostMapping(BASE + "/{productId}/submit")
    public ResponseEntity<String> submit(@PathVariable("productId") String productId, HttpServletRequest request) {
        return ok(service.submit(sellerId(request), productId(productId)));
    }

    @PostMapping(BASE + "/{productId}/archive")
    public ResponseEntity<String> archive(@PathVariable("productId") String productId, HttpServletRequest request) {
        return ok(service.archive(sellerId(request), productId(productId)));
    }

    @PostMapping(BASE + "/{productId}/restore")
    public ResponseEntity<String> restore(@PathVariable("productId") String productId, HttpServletRequest request) {
        return ok(service.restore(sellerId(request), productId(productId)));
    }

    private static ResponseEntity<String> ok(Product product) {
        return ResponseEntity.ok().contentType(StorefrontController.JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, SellerProductViews.etag(product)).body(Json.write(SellerProductViews.product(product)));
    }

    /** Профиль продавца из токена (Р-18). */
    private static UUID sellerId(HttpServletRequest request) {
        return CurrentUser.sellerId(CurrentUser.of(request));
    }

    private static UUID productId(String value) {
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Идентификатор товара должен быть UUID в нижнем регистре.");
        }
        return UUID.fromString(value);
    }

    private static Optional<ProductPage.After> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("c", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ProductPage.After(Instant.parse(values.get().get("c")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }
}
