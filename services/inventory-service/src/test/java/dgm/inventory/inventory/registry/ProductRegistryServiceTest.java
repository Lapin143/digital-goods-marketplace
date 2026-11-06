package dgm.inventory.inventory.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.stock.FakeApiStock;
import dgm.inventory.inventory.stock.domain.ApiStock;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Реестр товаров по событиям каталога: версия, статус, сообщения без значений (раздел 5 доменной модели). */
@Tag("TC-407")
class ProductRegistryServiceTest {

    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");

    private final Map<UUID, ProductCopy> rows = new HashMap<>();
    private final ProductCopyRepository repository = new ProductCopyRepository() {
        @Override
        public Optional<ProductCopy> find(UUID productId) {
            return Optional.ofNullable(rows.get(productId));
        }

        @Override
        public Optional<ProductCopy> lock(UUID productId) {
            return find(productId);
        }

        @Override
        public boolean apply(ProductCopy copy, Instant at) {
            ProductCopy old = rows.get(copy.productId());
            if (old != null && old.sourceVersion() >= copy.sourceVersion()) {
                return false;
            }
            rows.put(copy.productId(), copy);
            return true;
        }

        @Override
        public long bumpStockVersion(UUID productId, Instant at) {
            throw new UnsupportedOperationException("реестр товаров версию остатка не меняет");
        }

        @Override
        public boolean setStatus(UUID productId, String status, Instant at) {
            ProductCopy old = rows.get(productId);
            if (old == null) {
                return false;
            }
            rows.put(productId, new ProductCopy(productId, old.sellerId(), old.issuanceMethod(), status, old.sourceVersion()));
            return true;
        }
    };
    private final FakeApiStock apiStock = new FakeApiStock();
    private final ProductRegistryService service = new ProductRegistryService(repository, apiStock);

    private static Map<String, Object> data(String status, Object version) {
        return data("key_pool", status, version);
    }

    private static Map<String, Object> data(String issuanceMethod, String status, Object version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", PRODUCT.toString());
        data.put("sellerId", SELLER.toString());
        data.put("issuanceMethod", issuanceMethod);
        data.put("status", status);
        data.put("version", version);
        return data;
    }

    private static EventEnvelope event(String type, Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), "catalog-service", type, Instant.parse("2026-10-17T12:00:00Z"), PRODUCT.toString(), 1,
                "Product", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", UUID.randomUUID().toString(), data);
    }

    @Test
    void productCreatedMakesACopyWithOwnerIssuanceMethodStatusAndVersion() {
        assertTrue(service.apply(event("product.created", data("draft", 1))).applied());
        assertEquals(new ProductCopy(PRODUCT, SELLER, "key_pool", "draft", 1), rows.get(PRODUCT));
    }

    @Test
    void aNewerVersionUpdatesTheCopyAndAnOlderOrEqualOneIsIgnored() {
        service.apply(event("product.created", data("draft", 1)));
        assertTrue(service.apply(event("product.updated", data("on_moderation", 3))).applied());
        HandleResult stale = service.apply(event("product.updated", data("draft", 2)));
        assertFalse(stale.applied());
        assertEquals("stale-event", stale.reason());
        assertFalse(service.apply(event("product.updated", data("draft", 3))).applied());
        assertEquals("on_moderation", rows.get(PRODUCT).status());
        assertEquals(3, rows.get(PRODUCT).sourceVersion());
    }

    @Test
    void moderatorDecisionsChangeOnlyTheStatus() {
        service.apply(event("product.created", data("on_moderation", 2)));
        Map<String, Object> decision = Map.of("productId", PRODUCT.toString(), "sellerId", SELLER.toString(), "title", "Лунная тропа");
        assertTrue(service.apply(event("product.published", decision)).applied());
        assertEquals("published", rows.get(PRODUCT).status());
        service.apply(event("product.blocked", decision));
        assertEquals("blocked", rows.get(PRODUCT).status());
        service.apply(event("product.rejected", decision));
        assertEquals("rejected", rows.get(PRODUCT).status());
        assertEquals(2, rows.get(PRODUCT).sourceVersion(), "версия копии решением не меняется");
        assertEquals(SELLER, rows.get(PRODUCT).sellerId());
    }

    @Test
    void aDecisionForAnUnknownProductIsIgnored() {
        HandleResult result = service.apply(event("product.published", Map.of("productId", UUID.randomUUID().toString())));
        assertFalse(result.applied());
        assertEquals("unknown-product", result.reason());
    }

    @Test
    void aBrokenEventGoesStraightToTheDeadLetterQueueWithoutTheValue() {
        Map<String, Object> noVersion = data("draft", null);
        assertThrows(FatalEventException.class, () -> service.apply(event("product.created", noVersion)));
        assertThrows(FatalEventException.class, () -> service.apply(event("product.created", data("draft", 0))));
        assertThrows(FatalEventException.class, () -> service.apply(event("product.created", data("draft", "1"))));
        assertThrows(FatalEventException.class, () -> service.apply(event("product.created", data("странный", 1))));
        Map<String, Object> badId = data("draft", 1);
        badId.put("productId", "секретное-значение");
        FatalEventException e = assertThrows(FatalEventException.class, () -> service.apply(event("product.created", badId)));
        assertFalse(e.getMessage().contains("секретное"), "сообщение называет поле, не значение");
        assertThrows(FatalEventException.class, () -> service.apply(event("seller.approved", data("draft", 1))));
        assertEquals(Map.of(), rows);
    }

    @Tag("TC-415")
    @Test
    void anApiProductGetsAnEmptyStockRowTogetherWithItsCopy() {
        assertTrue(service.apply(event("product.created", data("seller_api", "draft", 1))).applied());
        assertEquals(new ProductCopy(PRODUCT, SELLER, "seller_api", "draft", 1), rows.get(PRODUCT));
        assertEquals(1, apiStock.rows.size());
        ApiStock row = apiStock.rows.get(PRODUCT);
        assertEquals(0, row.quantity(), "до правки продавцом заказать нечего (INV-09)");
        assertEquals(1, row.version());
        assertEquals(Instant.parse("2026-10-17T12:00:00Z"), row.updatedAt(), "время строки это время события");
    }

    @Tag("TC-415")
    @Test
    void laterEventsOfAnApiProductKeepTheQuantityTheSellerSet() {
        service.apply(event("product.created", data("seller_api", "draft", 1)));
        apiStock.rows.put(PRODUCT, new ApiStock(PRODUCT, 25, 4, Instant.parse("2026-10-18T09:00:00Z")));
        assertTrue(service.apply(event("product.updated", data("seller_api", "on_moderation", 2))).applied());
        assertTrue(service.apply(event("product.published", Map.of("productId", PRODUCT.toString()))).applied());
        assertEquals(25, apiStock.quantity(PRODUCT));
        assertEquals(4, apiStock.version(PRODUCT));
        assertEquals("published", rows.get(PRODUCT).status());
    }

    @Tag("TC-415")
    @Test
    void aKeyPoolProductAndAStaleEventCreateNoStockRow() {
        service.apply(event("product.created", data("key_pool", "draft", 2)));
        assertTrue(apiStock.rows.isEmpty(), "у товара с пулом остатка по API нет");
        HandleResult stale = service.apply(event("product.updated", data("seller_api", "draft", 1)));
        assertFalse(stale.applied());
        assertTrue(apiStock.rows.isEmpty(), "устаревшее событие строку не создаёт");
        assertEquals("key_pool", rows.get(PRODUCT).issuanceMethod());
    }

    @Test
    void theCopyKnowsWhoOwnsItAndWhenItAcceptsKeysOrApiStock() {
        ProductCopy api = new ProductCopy(PRODUCT, SELLER, "seller_api", "published", 1);
        assertTrue(api.sellerApi());
        assertFalse(api.keyPool());
        assertTrue(api.acceptsApiStock());
        for (String status : new String[] {"draft", "on_moderation", "rejected"}) {
            assertTrue(new ProductCopy(PRODUCT, SELLER, "seller_api", status, 1).acceptsApiStock(), status);
        }
        assertFalse(new ProductCopy(PRODUCT, SELLER, "seller_api", "blocked", 1).acceptsApiStock());
        assertFalse(new ProductCopy(PRODUCT, SELLER, "seller_api", "archived", 1).acceptsApiStock());
        ProductCopy pool = new ProductCopy(PRODUCT, SELLER, "key_pool", "published", 1);
        assertFalse(pool.sellerApi());
        assertTrue(pool.keyPool());
        assertFalse(pool.acceptsApiStock(), "товару с пулом остаток по API не задают");
    }

    @Test
    void theCopyKnowsWhoOwnsItAndWhenItAcceptsKeys() {
        ProductCopy copy = new ProductCopy(PRODUCT, SELLER, "key_pool", "published", 1);
        assertTrue(copy.ownedBy(SELLER));
        assertFalse(copy.ownedBy(UUID.randomUUID()));
        assertTrue(copy.acceptsKeys());
        assertFalse(new ProductCopy(PRODUCT, SELLER, "key_pool", "blocked", 1).acceptsKeys());
        assertFalse(new ProductCopy(PRODUCT, SELLER, "key_pool", "archived", 1).acceptsKeys());
        assertFalse(new ProductCopy(PRODUCT, SELLER, "seller_api", "published", 1).acceptsKeys());
    }
}
