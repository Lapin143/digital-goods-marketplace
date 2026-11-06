package dgm.inventory.inventory.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.registry.ProductCopy;
import dgm.inventory.inventory.reservation.FakeStock;
import dgm.inventory.inventory.service.StockPublisher;
import dgm.inventory.inventory.stock.domain.ApiStock;
import dgm.inventory.inventory.stock.repository.ApiStockRepository;
import dgm.inventory.inventory.stock.service.ApiStockService;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Правка остатка продавцом ({@code setApiStock}, ADR-008): владелец, способ выдачи, статус, версия из {@code If-Match}, повтор того же
 * значения и событие остатка. Настоящий условный {@code UPDATE}, триггер версии и гонки с резервом проверяет {@code ApiStockIT}.
 */
class ApiStockServiceTest {

    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-0000000002a2");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final Instant START = Instant.parse("2026-11-06T12:00:00Z");

    private final FakeStock.Products products = new FakeStock.Products();
    private final FakeStock.Tx transaction = new FakeStock.Tx();
    private final FakeApiStock stock = new FakeApiStock();
    private final ManualClock clock = new ManualClock(START);
    private ApiStockService service;

    @BeforeEach
    void setUp() {
        products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "seller_api", "published", 1));
        stock.rows.put(PRODUCT, new ApiStock(PRODUCT, 10, 3, START));
        service = new ApiStockService(products, stock, new StockPublisher(products, transaction, clock), transaction, clock);
    }

    private static ProblemException refused(Runnable call) {
        return assertThrows(ProblemException.class, call::run);
    }

    private List<Map<String, Object>> stockEvents() {
        return transaction.events.stream().filter(e -> e.type().equals("stock.changed")).map(FakeStock.Tx.Event::data).toList();
    }

    @Tag("TC-419")
    @Test
    void theSellerReplacesTheQuantityAndTheVersionAndTheEventAdvance() {
        ApiStock saved = service.set(SELLER, PRODUCT, 3, 25);
        assertEquals(25, saved.quantity());
        assertEquals(4, saved.version());
        assertEquals(saved, stock.rows.get(PRODUCT));
        assertEquals(List.of(Map.of("productId", PRODUCT.toString(), "available", 25, "version", 1L)), stockEvents());
    }

    @Tag("TC-419")
    @Test
    void zeroIsAllowedAndMeansNothingToSell() {
        ApiStock saved = service.set(SELLER, PRODUCT, 3, 0);
        assertEquals(0, saved.quantity());
        assertEquals(0, stockEvents().get(0).get("available"));
    }

    @Tag("TC-419")
    @Test
    void aStaleVersionIsRefusedWithTheCurrentQuantityAndTheCurrentETag() {
        ProblemException e = refused(() -> service.set(SELLER, PRODUCT, 2, 25));
        assertEquals(ProblemType.VERSION_CONFLICT, e.type());
        assertEquals(10, e.extensions().get("available"));
        assertEquals(Map.of("ETag", "\"3\""), e.headers());
        assertEquals(new ApiStock(PRODUCT, 10, 3, START), stock.rows.get(PRODUCT), "остаток не тронут");
        assertTrue(transaction.events.isEmpty(), "и событие не записано");
        assertEquals(0, stock.changes);
    }

    @Tag("TC-419")
    @Test
    void aVersionFromTheFutureIsRefusedToo() {
        assertEquals(ProblemType.VERSION_CONFLICT, refused(() -> service.set(SELLER, PRODUCT, 4, 25)).type());
        assertEquals(10, stock.quantity(PRODUCT));
    }

    @Tag("TC-419")
    @Test
    void anOrderThatArrivesWhileTheSellerEditsMakesTheEditStaleInsteadOfOverwritingIt() {
        stock.take(PRODUCT, 4);
        ProblemException e = refused(() -> service.set(SELLER, PRODUCT, 3, 25));
        assertEquals(ProblemType.VERSION_CONFLICT, e.type());
        assertEquals(6, e.extensions().get("available"));
        assertEquals(Map.of("ETag", "\"4\""), e.headers());
        assertEquals(6, stock.quantity(PRODUCT));
    }

    @Tag("TC-419")
    @Test
    void aRaceBetweenTheCheckAndTheUpdateIsStillAConflictWithTheFreshData() {
        ApiStockRepository racing = new ApiStockRepository() {
            @Override
            public boolean ensure(UUID productId, Instant at) {
                return stock.ensure(productId, at);
            }

            @Override
            public Optional<ApiStock> find(UUID productId) {
                return stock.find(productId);
            }

            @Override
            public Optional<ApiStock> take(UUID productId, int n) {
                return stock.take(productId, n);
            }

            @Override
            public ApiStock give(UUID productId, int n) {
                return stock.give(productId, n);
            }

            @Override
            public Optional<ApiStock> replace(UUID productId, int quantity, int expectedVersion) {
                stock.take(productId, 1);
                return stock.replace(productId, quantity, expectedVersion);
            }

            @Override
            public int activeUnits(UUID productId) {
                return stock.activeUnits(productId);
            }

            @Override
            public int usedUnits(UUID productId) {
                return stock.usedUnits(productId);
            }
        };
        ApiStockService racy = new ApiStockService(products, racing, new StockPublisher(products, transaction, clock), transaction, clock);
        ProblemException e = refused(() -> racy.set(SELLER, PRODUCT, 3, 25));
        assertEquals(ProblemType.VERSION_CONFLICT, e.type());
        assertEquals(9, e.extensions().get("available"), "отвечаем свежим остатком, а не прочитанным до гонки");
        assertEquals(Map.of("ETag", "\"4\""), e.headers());
        assertTrue(transaction.events.isEmpty());
    }

    @Tag("TC-419")
    @Test
    void theSameValueWithTheRightVersionChangesNothingAndPublishesNothing() {
        ApiStock same = service.set(SELLER, PRODUCT, 3, 10);
        assertEquals(new ApiStock(PRODUCT, 10, 3, START), same);
        assertEquals(0, stock.changes);
        assertTrue(transaction.events.isEmpty());
        ApiStock again = service.set(SELLER, PRODUCT, 3, 10);
        assertEquals(same, again, "повтор запроса безопасен");
    }

    @Tag("TC-419")
    @Test
    void aRepeatedEditWithTheOldVersionAfterASuccessfulOneIsAConflict() {
        service.set(SELLER, PRODUCT, 3, 25);
        ProblemException e = refused(() -> service.set(SELLER, PRODUCT, 3, 25));
        assertEquals(ProblemType.VERSION_CONFLICT, e.type());
        assertEquals(25, e.extensions().get("available"));
        assertEquals(Map.of("ETag", "\"4\""), e.headers());
        assertEquals(1, stockEvents().size());
    }

    @Tag("TC-419")
    @Test
    void aProductWithoutTheStockRowYetGetsItAtVersionOneAndAcceptsTheFirstEdit() {
        stock.rows.clear();
        ApiStock saved = service.set(SELLER, PRODUCT, 1, 7);
        assertEquals(new ApiStock(PRODUCT, 7, 2, saved.updatedAt()), saved);
        assertEquals(7, stockEvents().get(0).get("available"));
    }

    @Tag("TC-419")
    @Test
    void someoneElsesAndUnknownProductsLookTheSame() {
        ProblemException foreign = refused(() -> service.set(UUID.randomUUID(), PRODUCT, 3, 25));
        ProblemException unknown = refused(() -> service.set(SELLER, UUID.randomUUID(), 3, 25));
        assertEquals(ProblemType.NOT_FOUND, foreign.type());
        assertEquals(ProblemType.NOT_FOUND, unknown.type());
        assertEquals(foreign.getMessage(), unknown.getMessage(), "ответ не отличает чужой товар от несуществующего");
        assertEquals(10, stock.quantity(PRODUCT));
    }

    @Tag("TC-419")
    @Test
    void aKeyPoolProductHasNoApiStockAndNoRowIsCreatedForIt() {
        products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "key_pool", "published", 1));
        stock.rows.clear();
        ProblemException e = refused(() -> service.set(SELLER, PRODUCT, 1, 5));
        assertEquals(ProblemType.ISSUANCE_METHOD_MISMATCH, e.type());
        assertTrue(stock.rows.isEmpty(), "строка остатка товару с пулом не создаётся");
        assertTrue(transaction.events.isEmpty());
    }

    @Tag("TC-419")
    @Test
    void theMismatchIsReportedBeforeAnyVersionCheck() {
        products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "key_pool", "published", 1));
        assertEquals(ProblemType.ISSUANCE_METHOD_MISMATCH, refused(() -> service.set(SELLER, PRODUCT, 99, 5)).type());
    }

    @Tag("TC-419")
    @Test
    void blockedAndArchivedProductsAreRefusedAndTheOthersAreAccepted() {
        for (String status : new String[] {"blocked", "archived"}) {
            products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "seller_api", status, 1));
            ProblemException e = refused(() -> service.set(SELLER, PRODUCT, 3, 25));
            assertEquals(ProblemType.STATE_CONFLICT, e.type(), status);
            assertEquals(status, e.extensions().get("currentStatus"));
        }
        assertEquals(10, stock.quantity(PRODUCT));
        int version = 3;
        for (String status : new String[] {"draft", "on_moderation", "published", "rejected"}) {
            products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "seller_api", status, 1));
            assertEquals(version + 1, service.set(SELLER, PRODUCT, version, 20 + version).version(), status);
            version++;
        }
    }

    @Tag("TC-419")
    @Test
    void theEventVersionGrowsWithEveryAcceptedEditAndTheProductRowIsLockedFirst() {
        service.set(SELLER, PRODUCT, 3, 11);
        service.set(SELLER, PRODUCT, 4, 12);
        assertEquals(List.of(1L, 2L), stockEvents().stream().map(e -> e.get("version")).toList());
        assertEquals(List.of(11, 12), stockEvents().stream().map(e -> e.get("available")).toList());
        assertFalse(products.locked.isEmpty());
        assertEquals(PRODUCT, products.locked.get(0));
    }
}
