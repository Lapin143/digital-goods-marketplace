package dgm.catalog.catalog.seller.controller;

import dgm.catalog.catalog.controller.Views;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductForm;
import dgm.catalog.catalog.domain.ProductPage;
import dgm.kit.time.Timestamps;
import dgm.kit.web.IfMatch;
import dgm.kit.web.PageCursor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Тела ответов по схемам {@code SellerProduct} и {@code SellerProductPage} (catalog-service.yaml). */
public final class SellerProductViews {

    private SellerProductViews() {
    }

    /** ETag товара: версия в кавычках (conventions.md, раздел 8). */
    public static String etag(Product product) {
        return IfMatch.etag(product.version());
    }

    public static Map<String, Object> product(Product p) {
        ProductForm f = p.form();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", p.id().toString());
        body.put("sellerId", p.sellerId().toString());
        body.put("title", f.title());
        body.put("description", f.description());
        body.put("productType", f.productType().code());
        body.put("platform", f.platform());
        body.put("activationRegions", f.activationRegions());
        body.put("price", Views.money(f.price(), ProductForm.CURRENCY));
        body.put("issuanceMethod", f.issuanceMethod().code());
        body.put("status", p.status().code());
        body.put("rejectionReason", p.rejectionReason());
        body.put("blockReason", p.blockNote());
        body.put("submittedAt", time(p.submittedAt()));
        body.put("reviewDeadlineAt", time(p.reviewDeadlineAt()));
        body.put("version", p.version());
        body.put("createdAt", time(p.createdAt()));
        body.put("updatedAt", time(p.updatedAt()));
        return body;
    }

    static Map<String, Object> page(ProductPage page) {
        List<Object> items = new ArrayList<>();
        for (Product product : page.items()) {
            items.add(product(product));
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("limit", page.limit());
        info.put("nextCursor", page.next().map(n -> PageCursor.encode(Map.of("c", n.createdAt().toString(), "i", n.id().toString()))).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", info);
        return body;
    }

    private static String time(Instant instant) {
        return instant == null ? null : Timestamps.format(instant);
    }
}
