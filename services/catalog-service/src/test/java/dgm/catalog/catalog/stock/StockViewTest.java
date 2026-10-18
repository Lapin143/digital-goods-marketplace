package dgm.catalog.catalog.stock;

import static dgm.catalog.catalog.FakeProducts.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.catalog.FakeCatalogTransaction;
import dgm.catalog.catalog.cache.MemoryCache;
import dgm.catalog.catalog.repository.StockViewRepository;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Обработчик {@code stock.changed}: разбор события, порядок по метке времени (Р-15), неизвестный товар, сброс кэша после фиксации. */
@Tag("TC-319")
@Tag("TC-321")
class StockViewTest {

    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    /** Копия остатка в памяти с теми же правилами, что в SQL: запись, если метка не меньше сохранённой. */
    private static final class Rows implements StockViewRepository {
        private final Map<UUID, Integer> available = new HashMap<>();
        private final Map<UUID, Instant> times = new HashMap<>();
        private final Set<UUID> products = new HashSet<>();

        @Override
        public Outcome apply(UUID productId, int value, Instant eventTime) {
            if (!products.contains(productId)) {
                return Outcome.UNKNOWN_PRODUCT;
            }
            Instant saved = times.get(productId);
            if (saved != null && saved.isAfter(eventTime)) {
                return Outcome.STALE;
            }
            available.put(productId, value);
            times.put(productId, eventTime);
            return Outcome.APPLIED;
        }
    }

    private Rows rows;
    private FakeCatalogTransaction transaction;
    private MemoryCache memory;
    private StockViewHandler handler;

    @BeforeEach
    void setUp() {
        rows = new Rows();
        rows.products.add(id(1));
        transaction = new FakeCatalogTransaction();
        memory = new MemoryCache();
        handler = new StockViewHandler(new StockViewService(rows, transaction, memory.cache()));
    }

    private static EventEnvelope event(Map<String, Object> data, Instant time) {
        return new EventEnvelope(UUID.randomUUID(), "inventory-service", "stock.changed", time, String.valueOf(data.get("productId")), 1,
                "ProductStock", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", UUID.randomUUID().toString(), data);
    }

    private static Map<String, Object> data(UUID productId, Object available) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", productId.toString());
        data.put("available", available);
        data.put("version", 1);
        return data;
    }

    private HandleResult handle(EventEnvelope event) {
        return transaction.execute(() -> handler.handle(event));
    }

    @Test
    void theHandlerIsRegisteredForStockChangedOfInventoryEvents() {
        assertEquals("stock-view-service", handler.name());
        assertEquals("inventory.events", handler.topic());
        assertEquals(Set.of("stock.changed"), handler.types());
        assertEquals(1, handler.maxSchemaVersion());
        assertEquals("catalog", handler.module());
    }

    @Test
    void theNumberOfFreeKeysIsStored() {
        assertTrue(handle(event(data(id(1), 7), T0)).applied());
        assertEquals(7, rows.available.get(id(1)));
    }

    @Test
    void aZeroStockIsStoredToo() {
        handle(event(data(id(1), 5), T0));
        assertTrue(handle(event(data(id(1), 0), T0.plusSeconds(1))).applied());
        assertEquals(0, rows.available.get(id(1)));
    }

    @Test
    void anOlderEventAfterANewerOneDoesNotRollTheStockBack() {
        handle(event(data(id(1), 3), T0.plusSeconds(10)));
        HandleResult late = handle(event(data(id(1), 99), T0));
        assertFalse(late.applied());
        assertEquals("stale-event", late.reason());
        assertEquals(3, rows.available.get(id(1)));
    }

    @Test
    void anEventWithTheSameTimestampIsApplied() {
        handle(event(data(id(1), 3), T0));
        assertTrue(handle(event(data(id(1), 4), T0)).applied());
        assertEquals(4, rows.available.get(id(1)));
    }

    @Test
    void anUnknownProductIsIgnoredNotRetried() {
        HandleResult result = handle(event(data(id(2), 3), T0));
        assertFalse(result.applied());
        assertEquals("unknown-product", result.reason());
        assertEquals(0, transaction.afterCommitRuns());
    }

    @Test
    void theCachedCardIsDroppedAfterTheStockChangesButNotForAnIgnoredEvent() {
        memory.set("catalog:product:" + id(1), "{}", java.time.Duration.ofSeconds(30));
        handle(event(data(id(1), 3), T0.plusSeconds(10)));
        assertFalse(memory.has(id(1)));
        memory.set("catalog:product:" + id(1), "{}", java.time.Duration.ofSeconds(30));
        handle(event(data(id(1), 1), T0));
        assertTrue(memory.has(id(1)), "устаревшее событие карточку не сбрасывает");
    }

    @Test
    void aBrokenEventGoesStraightToTheDeadLetterQueue() {
        assertThrows(FatalEventException.class, () -> handle(event(Map.of("productId", "не-uuid", "available", 1), T0)));
        assertThrows(FatalEventException.class, () -> handle(event(Map.of("available", 1), T0)));
        assertThrows(FatalEventException.class, () -> handle(event(data(id(1), -1), T0)));
        assertThrows(FatalEventException.class, () -> handle(event(data(id(1), "5"), T0)));
        assertThrows(FatalEventException.class, () -> handle(event(data(id(1), 1.5), T0)));
        assertThrows(FatalEventException.class, () -> handle(event(Map.of("productId", id(1).toString()), T0)));
    }

    @Test
    void anUnavailableRedisDoesNotFailTheEvent() {
        memory.down(true);
        assertTrue(handle(event(data(id(1), 2), T0)).applied());
        assertEquals(2, rows.available.get(id(1)));
    }
}
