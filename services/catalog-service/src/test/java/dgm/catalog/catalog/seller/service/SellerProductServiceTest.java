package dgm.catalog.catalog.seller.service;

import static dgm.catalog.catalog.seller.Products.OTHER_SELLER;
import static dgm.catalog.catalog.seller.Products.REVIEW;
import static dgm.catalog.catalog.seller.Products.SELLER;
import static dgm.catalog.catalog.seller.Products.T0;
import static dgm.catalog.catalog.seller.Products.form;
import static dgm.catalog.catalog.seller.Products.id;
import static dgm.catalog.catalog.seller.Products.in;
import static dgm.catalog.catalog.seller.Products.withPrice;
import static dgm.catalog.catalog.seller.Products.withTitle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.catalog.FakeCatalogTransaction;
import dgm.catalog.catalog.cache.MemoryCache;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductPage;
import dgm.catalog.catalog.domain.ProductSettings;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.seller.FakeApprovedSellers;
import dgm.catalog.catalog.seller.FakeSellerProducts;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("TC-301")
@Tag("TC-303")
@Tag("TC-304")
@Tag("TC-305")
@Tag("TC-306")
@Tag("TC-308")
@Tag("TC-309")
@Tag("TC-317")
@Tag("TC-319")
class SellerProductServiceTest {

    private ManualClock clock;
    private FakeSellerProducts products;
    private FakeCatalogTransaction transaction;
    private final MemoryCache memory = new MemoryCache();
    private FakeApprovedSellers sellers;
    private SellerProductService service;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        products = new FakeSellerProducts();
        transaction = new FakeCatalogTransaction();
        sellers = new FakeApprovedSellers().approve(SELLER).approve(OTHER_SELLER);
        service = new SellerProductService(products, transaction, sellers, clock, new ProductSettings(REVIEW), memory.cache());
    }

    private static ProblemType problem(Runnable action) {
        return assertThrows(ProblemException.class, action::run).type();
    }

    private Product created() {
        return service.create(SELLER, form());
    }

    // ------------------------------------------------------------------ создание

    @Test
    void createStoresADraftOfTheSellerAndWritesProductCreatedInTheSameTransaction() {
        Product p = created();
        assertEquals(ProductStatus.DRAFT, p.status());
        assertEquals(SELLER, p.sellerId());
        assertEquals(1, p.version());
        assertEquals(p, products.row(p.id()));
        assertEquals(1, transaction.executions());
        List<FakeCatalogTransaction.Published> events = transaction.published();
        assertEquals(1, events.size());
        FakeCatalogTransaction.Published event = events.get(0);
        assertEquals("product.created", event.eventType());
        assertEquals("catalog.events", event.topic());
        assertEquals("Product", event.aggregateType());
        assertEquals(p.id().toString(), event.aggregateId());
        assertEquals(1, event.schemaVersion());
        assertEquals(Map.of("productId", p.id().toString(), "sellerId", SELLER.toString(), "productType", "game_key", "issuanceMethod", "key_pool",
                "status", "draft", "version", 1), event.data());
    }

    @Test
    void createUsesTimeOrderedIdentifiers() {
        Product first = created();
        clock.advance(Duration.ofSeconds(1));
        Product second = created();
        assertTrue(first.id().compareTo(second.id()) != 0);
        assertEquals(T0.plusSeconds(1), second.createdAt());
    }

    @Test
    void anUnapprovedSellerCannotCreateAndNothingIsWritten() {
        sellers.revoke(SELLER);
        assertEquals(ProblemType.FORBIDDEN, problem(() -> created()));
        assertEquals(0, products.count());
        assertEquals(List.of(), transaction.published());
    }

    @Test
    void eventsOfAFailedCreateAreRolledBack() {
        FakeSellerProducts broken = new FakeSellerProducts() {
            @Override
            public void insert(Product product) {
                throw new IllegalStateException("база недоступна");
            }
        };
        SellerProductService failing = new SellerProductService(broken, transaction, sellers, clock, new ProductSettings(REVIEW), memory.cache());
        assertThrows(IllegalStateException.class, () -> failing.create(SELLER, form()));
        assertEquals(List.of(), transaction.published());
    }

    // ------------------------------------------------------------------ чтение

    @Test
    void getReturnsOwnProductAndForeignOrMissingIsNotFound() {
        Product mine = created();
        assertEquals(mine, service.get(SELLER, mine.id()));
        assertEquals(ProblemType.NOT_FOUND, problem(() -> service.get(OTHER_SELLER, mine.id())));
        assertEquals(ProblemType.NOT_FOUND, problem(() -> service.get(SELLER, id(99))));
    }

    @Test
    void listShowsNewestFirstOnlyOwnProductsAndPages() {
        for (int i = 1; i <= 5; i++) {
            products.with(in(id(i), SELLER, form(), ProductStatus.DRAFT, 1, T0.plusSeconds(i)));
        }
        products.with(in(id(50), OTHER_SELLER, form(), ProductStatus.DRAFT, 1, T0.plusSeconds(100)));

        ProductPage first = service.list(SELLER, List.of(), 2, Optional.empty());
        assertEquals(List.of(id(5), id(4)), first.items().stream().map(Product::id).toList());
        assertEquals(2, first.limit());
        ProductPage.After next = first.next().orElseThrow();
        assertEquals(id(4), next.id());

        ProductPage second = service.list(SELLER, List.of(), 2, Optional.of(next));
        assertEquals(List.of(id(3), id(2)), second.items().stream().map(Product::id).toList());
        ProductPage last = service.list(SELLER, List.of(), 2, second.next());
        assertEquals(List.of(id(1)), last.items().stream().map(Product::id).toList());
        assertTrue(last.next().isEmpty());
    }

    @Test
    void listFiltersByStatuses() {
        products.with(in(id(1), SELLER, form(), ProductStatus.DRAFT, 1, T0.plusSeconds(1)),
                in(id(2), SELLER, form(), ProductStatus.REJECTED, 1, T0.plusSeconds(2)),
                in(id(3), SELLER, form(), ProductStatus.PUBLISHED, 1, T0.plusSeconds(3)));
        ProductPage page = service.list(SELLER, List.of(ProductStatus.DRAFT, ProductStatus.REJECTED), 20, Optional.empty());
        assertEquals(List.of(id(2), id(1)), page.items().stream().map(Product::id).toList());
        assertTrue(page.next().isEmpty());
    }

    @Test
    void listOfAFullPageWithoutAnotherRowHasNoNextCursor() {
        products.with(in(id(1), SELLER, form(), ProductStatus.DRAFT, 1, T0), in(id(2), SELLER, form(), ProductStatus.DRAFT, 1, T0.plusSeconds(1)));
        ProductPage page = service.list(SELLER, List.of(), 2, Optional.empty());
        assertEquals(2, page.items().size());
        assertTrue(page.next().isEmpty());
    }

    // ------------------------------------------------------------------ правка

    @Test
    void replaceSavesAndWritesProductUpdatedWithChangedFields() {
        Product p = created();
        clock.advance(Duration.ofMinutes(5));
        Product after = service.replace(SELLER, p.id(), 1, withTitle(form(), "Новое название"));
        assertEquals(2, after.version());
        assertEquals("Новое название", products.row(p.id()).form().title());
        FakeCatalogTransaction.Published event = transaction.published("product.updated").get(0);
        assertEquals(Map.of("productId", p.id().toString(), "sellerId", SELLER.toString(), "issuanceMethod", "key_pool", "status", "draft",
                "version", 2, "changedFields", List.of("title")), event.data());
    }

    @Test
    void replaceOfAPublishedProductWithANewTitleGoesToModerationAndTheEventSaysSo() {
        products.with(in(ProductStatus.PUBLISHED, 3));
        Product after = service.replace(SELLER, id(1), 3, withTitle(form(), "Другое"));
        assertEquals(ProductStatus.ON_MODERATION, after.status());
        assertEquals(T0.plus(REVIEW), after.reviewDeadlineAt());
        Map<String, Object> data = transaction.published("product.updated").get(0).data();
        assertEquals("on_moderation", data.get("status"));
        assertEquals(List.of("title", "status"), data.get("changedFields"));
    }

    @Test
    void replaceOfAPublishedProductWithANewPriceStaysPublishedWithoutModeration() {
        products.with(in(ProductStatus.PUBLISHED, 3));
        Product after = service.replace(SELLER, id(1), 3, withPrice(form(), 99_900L));
        assertEquals(ProductStatus.PUBLISHED, after.status());
        assertEquals(4, after.version());
        assertEquals(List.of("price"), transaction.published("product.updated").get(0).data().get("changedFields"));
    }

    @Test
    void replaceWithoutDifferencesWritesNothing() {
        Product p = created();
        int events = transaction.published().size();
        Product same = service.replace(SELLER, p.id(), 1, form());
        assertEquals(1, same.version());
        assertEquals(events, transaction.published().size());
        assertEquals(0, products.updates());
    }

    @Test
    void replaceWithAStaleVersionIsRefusedAndWritesNothing() {
        Product p = created();
        service.replace(SELLER, p.id(), 1, withTitle(form(), "Первая правка"));
        int events = transaction.published().size();
        assertEquals(ProblemType.VERSION_CONFLICT, problem(() -> service.replace(SELLER, p.id(), 1, withTitle(form(), "Вторая правка"))));
        assertEquals("Первая правка", products.row(p.id()).form().title());
        assertEquals(events, transaction.published().size());
    }

    @Test
    void replaceChecksTheStatusBeforeTheVersion() {
        products.with(in(ProductStatus.ON_MODERATION, 5));
        assertEquals(ProblemType.STATE_CONFLICT, problem(() -> service.replace(SELLER, id(1), 1, withTitle(form(), "Другое"))));
    }

    @Test
    void replaceOfAForeignProductIsNotFoundAndLeavesItUntouched() {
        Product p = created();
        assertEquals(ProblemType.NOT_FOUND, problem(() -> service.replace(OTHER_SELLER, p.id(), 1, withTitle(form(), "Чужое"))));
        assertEquals(p, products.row(p.id()));
    }

    @Test
    void aLostRaceInTheStoreIsAVersionConflictAndRollsBackTheEvent() {
        Product p = created();
        int events = transaction.published().size();
        FakeSellerProducts racing = new FakeSellerProducts() {
            @Override
            public boolean update(Product product, int expectedVersion) {
                return false;
            }
        }.with(p);
        SellerProductService racingService = new SellerProductService(racing, transaction, sellers, clock, new ProductSettings(REVIEW), memory.cache());
        assertEquals(ProblemType.VERSION_CONFLICT, problem(() -> racingService.replace(SELLER, p.id(), 1, withTitle(form(), "Гонка"))));
        assertEquals(events, transaction.published().size());
    }

    // ------------------------------------------------------------------ отправка, архив, восстановление

    @Test
    void submitPutsTheProductInTheQueueAndWritesTheStatusEvent() {
        Product p = created();
        clock.advance(Duration.ofHours(1));
        Product submitted = service.submit(SELLER, p.id());
        assertEquals(ProductStatus.ON_MODERATION, submitted.status());
        assertEquals(T0.plus(Duration.ofHours(1)), submitted.submittedAt());
        assertEquals(T0.plus(Duration.ofHours(1)).plus(REVIEW), submitted.reviewDeadlineAt());
        Map<String, Object> data = transaction.published("product.updated").get(0).data();
        assertEquals(List.of("status"), data.get("changedFields"));
        assertEquals("on_moderation", data.get("status"));
        assertEquals(2, data.get("version"));
    }

    @Test
    void secondSubmitIsAConflictAndKeepsTheFirstDeadline() {
        Product p = created();
        Product submitted = service.submit(SELLER, p.id());
        clock.advance(Duration.ofHours(5));
        assertEquals(ProblemType.STATE_CONFLICT, problem(() -> service.submit(SELLER, p.id())));
        assertEquals(submitted.reviewDeadlineAt(), products.row(p.id()).reviewDeadlineAt());
        assertEquals(1, transaction.published("product.updated").size());
    }

    @Test
    void archiveAndRestoreRoundTripWritesTwoEvents() {
        Product p = created();
        Product archived = service.archive(SELLER, p.id());
        assertEquals(ProductStatus.ARCHIVED, archived.status());
        Product restored = service.restore(SELLER, p.id());
        assertEquals(ProductStatus.DRAFT, restored.status());
        assertEquals(3, restored.version());
        List<FakeCatalogTransaction.Published> updates = transaction.published("product.updated");
        assertEquals(2, updates.size());
        assertEquals("archived", updates.get(0).data().get("status"));
        assertEquals("draft", updates.get(1).data().get("status"));
    }

    @Test
    void anActionOnAForeignProductIsNotFound() {
        Product p = created();
        assertEquals(ProblemType.NOT_FOUND, problem(() -> service.submit(OTHER_SELLER, p.id())));
        assertEquals(ProblemType.NOT_FOUND, problem(() -> service.archive(OTHER_SELLER, p.id())));
        assertEquals(ProblemType.NOT_FOUND, problem(() -> service.restore(OTHER_SELLER, p.id())));
        assertEquals(ProductStatus.DRAFT, products.row(p.id()).status());
    }

    @Test
    void everyOperationNeedsAnApprovedSeller() {
        Product p = created();
        sellers.revoke(SELLER);
        UUID id = p.id();
        for (Runnable call : List.<Runnable>of(() -> service.get(SELLER, id), () -> service.list(SELLER, List.of(), 20, Optional.empty()),
                () -> service.replace(SELLER, id, 1, withTitle(form(), "Другое")), () -> service.submit(SELLER, id),
                () -> service.archive(SELLER, id), () -> service.restore(SELLER, id))) {
            assertEquals(ProblemType.FORBIDDEN, problem(call));
        }
        assertEquals(ProductStatus.DRAFT, products.row(id).status());
        assertNotNull(products.row(id));
        assertFalse(transaction.published("product.updated").size() > 0);
    }

    // ------------------------------------------------------------------ кэш витрины

    @Test
    void aChangeToAPublishedProductDropsItsCachedCardAfterCommit() {
        products.with(in(ProductStatus.PUBLISHED, 3));
        memory.set("catalog:product:" + id(1), "{}", Duration.ofSeconds(30));
        service.replace(SELLER, id(1), 3, withPrice(form(), 99_900L));
        assertFalse(memory.has(id(1)), "карточка с прежней ценой в кэше не остаётся");
        assertEquals(1, transaction.afterCommitRuns());
    }

    @Test
    void archivingDropsTheCachedCardToo() {
        products.with(in(ProductStatus.PUBLISHED, 3));
        memory.set("catalog:product:" + id(1), "{}", Duration.ofSeconds(30));
        service.archive(SELLER, id(1));
        assertFalse(memory.has(id(1)));
    }

    @Test
    void anEditWithoutDifferencesLeavesTheCachedCardAlone() {
        products.with(in(ProductStatus.PUBLISHED, 3));
        memory.set("catalog:product:" + id(1), "{}", Duration.ofSeconds(30));
        service.replace(SELLER, id(1), 3, form());
        assertTrue(memory.has(id(1)));
    }

    @Test
    void aRefusedChangeKeepsTheCachedCard() {
        products.with(in(ProductStatus.PUBLISHED, 3));
        memory.set("catalog:product:" + id(1), "{}", Duration.ofSeconds(30));
        assertEquals(ProblemType.VERSION_CONFLICT, problem(() -> service.replace(SELLER, id(1), 2, withPrice(form(), 99_900L))));
        assertTrue(memory.has(id(1)), "откатившаяся транзакция кэш не трогает");
        assertEquals(0, transaction.afterCommitRuns());
    }

    @Test
    void anUnavailableRedisDoesNotBreakTheChange() {
        products.with(in(ProductStatus.PUBLISHED, 3));
        memory.down(true);
        Product after = service.replace(SELLER, id(1), 3, withPrice(form(), 99_900L));
        assertEquals(4, after.version());
        assertEquals(99_900L, products.row(id(1)).form().price());
    }
}
