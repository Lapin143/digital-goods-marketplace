package dgm.catalog.catalog.controller;

import dgm.catalog.catalog.domain.StorefrontFilter;
import dgm.catalog.catalog.domain.StorefrontItem;
import dgm.catalog.catalog.domain.StorefrontPage;
import dgm.catalog.catalog.service.StorefrontService;
import dgm.kit.json.Json;
import dgm.kit.web.Etags;
import dgm.kit.web.PageCursor;
import dgm.kit.web.QueryParams;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code listStorefrontProducts}: каталог, поиск и фильтры. Публичный маршрут, ответ кэшируется на 30 секунд (OP-28). */
@RestController
public class StorefrontController {

    static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final Set<String> PRODUCT_TYPES = Set.of("game_key", "software_license", "gift_card", "subscription");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    private final StorefrontService service;

    public StorefrontController(StorefrontService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/products")
    public ResponseEntity<String> list(HttpServletRequest request) {
        QueryParams params = QueryParams.of(request, "q", "productType", "platform", "region", "priceFrom", "priceTo");
        Optional<String> q = params.text("q", 1, 100);
        Optional<List<String>> types = params.list("productType", PRODUCT_TYPES);
        Optional<String> platform = params.text("platform", 1, 64);
        Optional<String> region = params.matching("region", COUNTRY, 2);
        OptionalLong priceFrom = params.integer("priceFrom", 0, 9_007_199_254_740_991L);
        OptionalLong priceTo = params.integer("priceTo", 0, 9_007_199_254_740_991L);
        if (priceFrom.isPresent() && priceTo.isPresent() && priceFrom.getAsLong() > priceTo.getAsLong()) {
            params.error("priceFrom", "out_of_range", "Нижняя граница цены больше верхней.");
        }
        int limit = params.limit();
        Optional<StorefrontFilter.After> after = cursor(params);
        params.check();

        StorefrontFilter filter = new StorefrontFilter(q, types, platform, region, boxed(priceFrom), boxed(priceTo), limit, after);
        String body = Json.write(toJson(service.list(filter)));
        String etag = Etags.of(body);
        if (Etags.matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().contentType(JSON).eTag(etag).header(HttpHeaders.CACHE_CONTROL, "public, max-age=30").body(body);
    }

    private static Optional<StorefrontFilter.After> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("p", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StorefrontFilter.After(Instant.parse(values.get().get("p")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }

    private static Optional<Long> boxed(OptionalLong value) {
        return value.isPresent() ? Optional.of(value.getAsLong()) : Optional.empty();
    }

    static Map<String, Object> toJson(StorefrontPage page) {
        List<Object> items = new ArrayList<>();
        for (StorefrontItem item : page.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", item.id().toString());
            row.put("title", item.title());
            row.put("productType", item.productType());
            row.put("platform", item.platform());
            row.put("price", Views.money(item.price(), item.currency()));
            row.put("inStock", item.inStock());
            items.add(row);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("limit", page.limit());
        info.put("nextCursor", page.next().map(n -> PageCursor.encode(Map.of("p", n.publishedAt().toString(), "i", n.id().toString()))).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", info);
        return body;
    }
}
