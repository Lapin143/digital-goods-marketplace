package dgm.catalog.catalog.moderation.service;

import static dgm.catalog.catalog.seller.Products.REVIEW;
import static dgm.catalog.catalog.seller.Products.SELLER;
import static dgm.catalog.catalog.seller.Products.T0;
import static dgm.catalog.catalog.seller.Products.form;
import static dgm.catalog.catalog.seller.Products.id;
import static dgm.catalog.catalog.seller.Products.in;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.catalog.FakeCatalogTransaction;
import dgm.catalog.catalog.FakeCatalogTransaction.Published;
import dgm.catalog.catalog.cache.MemoryCache;
import dgm.catalog.catalog.domain.ModerationQueuePage;
import dgm.catalog.catalog.domain.Moderator;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.seller.FakeSellerProducts;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Решения модератора по товарам: решение, событие и запись аудита одной транзакцией, повтор и второй модератор получают 409, черновик
 * для модератора не существует, кэш витрины сбрасывается после фиксации, очередь идёт от старых товаров к новым.
 */
@Tag("TC-310")
@Tag("TC-311")
@Tag("TC-312")
@Tag("TC-313")
@Tag("TC-317")
@Tag("TC-319")
class ProductModerationServiceTest {

    private static final UUID MODERATOR_ID = UUID.fromString("5d3b1f79-8c2e-4a60-b4d8-6e0a2c4f1b37");
    private static final Moderator MODERATOR = new Moderator(MODERATOR_ID, "203.0.113.7");
    private static final Set<String> AUDIT_FIELDS = Set.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes",
            "actorIp", "actorIpHashed", "correlationId");

    private ManualClock clock;
    private FakeSellerProducts products;
    private FakeCatalogTransaction transaction;
    private MemoryCache memory;
    private ProductModerationService service;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0.plus(Duration.ofHours(1)));
        products = new FakeSellerProducts();
        transaction = new FakeCatalogTransaction();
        memory = new MemoryCache();
        service = new ProductModerationService(products, transaction, memory.cache(), clock);
    }

    private static ProblemException refused(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    private static Product queued(int n, Instant submittedAt) {
        Product base = in(id(n), SELLER, form(), ProductStatus.ON_MODERATION, 2, T0);
        return new Product(base.id(), base.sellerId(), base.form(), null, null, null, null, submittedAt, submittedAt.plus(REVIEW), null, null,
                ProductStatus.ON_MODERATION, 2, T0, T0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> changeSummary(Map<String, Object> audit) {
        return ((List<Map<String, Object>>) audit.get("changes")).stream().map(c -> c.get("field") + ":" + c.get("before") + ">" + c.get("after")).toList();
    }

    // ---------------------------------------------------------------- одобрить

    @Test
    void approveWritesTheStatusTheEventAndTheAuditRecordTogether() {
        products.with(queued(1, T0));
        Product published = service.approve(MODERATOR, id(1));
        assertEquals(ProductStatus.PUBLISHED, products.row(id(1)).status());
        assertEquals(3, published.version());
        assertEquals(clock.instant(), published.publishedAt());

        List<Published> events = transaction.published();
        assertEquals(2, events.size());
        Published decision = events.get(0);
        assertEquals("catalog.events", decision.topic());
        assertEquals("Product", decision.aggregateType());
        assertEquals(id(1).toString(), decision.aggregateId());
        assertEquals("product.published", decision.eventType());
        assertEquals(1, decision.schemaVersion());
        assertEquals(Map.of("productId", id(1).toString(), "sellerId", SELLER.toString(), "title", form().title()), decision.data());

        Published audit = events.get(1);
        assertEquals("audit.events", audit.topic());
        assertEquals("audit.recorded", audit.eventType());
        assertEquals(AUDIT_FIELDS, audit.data().keySet());
        assertEquals("product.published", audit.data().get("action"));
        assertEquals("product", audit.data().get("objectType"));
        assertEquals(id(1).toString(), audit.data().get("objectId"));
        assertEquals("moderator", audit.data().get("actorRole"));
        assertEquals(MODERATOR_ID.toString(), audit.data().get("actorId"));
        assertEquals("203.0.113.7", audit.data().get("actorIp"));
        assertEquals(List.of("status:on_moderation>published"), changeSummary(audit.data()));
    }

    @Test
    void theCachedCardIsDroppedAfterTheDecisionCommits() {
        products.with(queued(1, T0));
        memory.set("catalog:product:" + id(1), "{}", Duration.ofSeconds(30));
        service.approve(MODERATOR, id(1));
        assertFalse(memory.has(id(1)));
        assertEquals(1, transaction.afterCommitRuns());
    }

    @Test
    void aSecondApproveGetsAStateConflictWithTheCurrentStatusAndWritesNothingMore() {
        products.with(queued(1, T0));
        service.approve(MODERATOR, id(1));
        int events = transaction.published().size();
        ProblemException e = refused(() -> service.approve(new Moderator(UUID.randomUUID(), "198.51.100.1"), id(1)));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("published", e.extensions().get("currentStatus"));
        assertEquals(events, transaction.published().size());
        assertEquals(3, products.row(id(1)).version());
    }

    // ---------------------------------------------------------------- отклонить

    @Test
    void rejectWritesTheReasonIntoTheProductTheEventAndTheAudit() {
        products.with(queued(1, T0));
        Product rejected = service.reject(MODERATOR, id(1), "В описании есть запрещённое содержимое");
        assertEquals(ProductStatus.REJECTED, rejected.status());
        assertEquals("В описании есть запрещённое содержимое", products.row(id(1)).rejectionReason());

        Published decision = transaction.published("product.rejected").get(0);
        assertEquals(Map.of("productId", id(1).toString(), "sellerId", SELLER.toString(), "title", form().title(),
                "reason", "В описании есть запрещённое содержимое"), decision.data());
        Published audit = transaction.published("audit.recorded").get(0);
        assertEquals("product.rejected", audit.data().get("action"));
        assertEquals(List.of("status:on_moderation>rejected", "decisionReason:null>В описании есть запрещённое содержимое"), changeSummary(audit.data()));
    }

    // ---------------------------------------------------------------- заблокировать

    @Test
    void blockTakesThePublishedProductDownAndSaysWhy() {
        products.with(in(ProductStatus.PUBLISHED, 4));
        Product blocked = service.block(MODERATOR, id(1), "Жалоба правообладателя");
        assertEquals(ProductStatus.BLOCKED, blocked.status());
        assertEquals("Жалоба правообладателя", products.row(id(1)).blockNote());

        Published decision = transaction.published("product.blocked").get(0);
        assertEquals(Map.of("productId", id(1).toString(), "sellerId", SELLER.toString(), "title", form().title(),
                "reason", "Жалоба правообладателя", "cause", "moderator_decision"), decision.data());
        Published audit = transaction.published("audit.recorded").get(0);
        assertEquals(List.of("status:published>blocked", "decisionReason:null>Жалоба правообладателя"), changeSummary(audit.data()));
    }

    @Test
    void blockingAnUnpublishedProductIsAStateConflict() {
        products.with(queued(1, T0));
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> service.block(MODERATOR, id(1), "Причина")).type());
        assertTrue(transaction.published().isEmpty());
    }

    // ---------------------------------------------------------------- черновик и неизвестный товар

    @Test
    void aDraftDoesNotExistForTheModerator() {
        products.with(in(ProductStatus.DRAFT, 1));
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.card(id(1))).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.approve(MODERATOR, id(1))).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.reject(MODERATOR, id(1), "Причина")).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.block(MODERATOR, id(1), "Причина")).type());
        assertEquals(1, products.row(id(1)).version());
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void anUnknownProductIsNotFound() {
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.card(id(9))).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.approve(MODERATOR, id(9))).type());
    }

    @Test
    void theCardIsOpenForEveryStatusButDraftAndChangesNothing() {
        products.with(in(ProductStatus.PUBLISHED, 3));
        Product card = service.card(id(1));
        assertEquals(ProductStatus.PUBLISHED, card.status());
        assertEquals(3, products.row(id(1)).version());
        assertTrue(transaction.published().isEmpty());
    }

    // ---------------------------------------------------------------- откат

    @Test
    void aFailedWriteLeavesNoEventNoAuditAndNoCacheReset() {
        FakeSellerProducts racing = new FakeSellerProducts() {
            @Override
            public boolean update(Product product, int expectedVersion) {
                return false;
            }
        }.with(queued(1, T0));
        ProductModerationService racingService = new ProductModerationService(racing, transaction, memory.cache(), clock);
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> racingService.approve(MODERATOR, id(1))).type());
        assertTrue(transaction.published().isEmpty());
        assertEquals(0, transaction.afterCommitRuns());
    }

    // ---------------------------------------------------------------- очередь

    @Test
    void theQueueGoesFromTheOldestSubmissionAndFlagsOverdueOnes() {
        Instant t = T0;
        products.with(queued(3, t.plusSeconds(300)), queued(1, t), queued(2, t.plusSeconds(100)), in(id(5), SELLER, form(), ProductStatus.PUBLISHED, 2, T0));
        // Срок первого вышел на 150 секунд назад, второго на 50, у третьего ещё идёт
        clock.set(t.plus(REVIEW).plusSeconds(150));
        ModerationQueuePage page = service.queue(10, Optional.empty());
        assertEquals(List.of(id(1), id(2), id(3)), page.items().stream().map(i -> i.productId()).toList());
        assertTrue(page.items().get(0).overdue(page.at()), "срок первого товара вышел");
        assertTrue(page.items().get(1).overdue(page.at()));
        assertFalse(page.items().get(2).overdue(page.at()), "срок третьего ещё идёт");
        assertTrue(page.next().isEmpty());
    }

    @Test
    void pagingWalksTheWholeQueueOnceWithoutGapsOrRepeats() {
        for (int n = 1; n <= 5; n++) {
            products.with(queued(n, T0.plusSeconds(n * 10L)));
        }
        ModerationQueuePage first = service.queue(2, Optional.empty());
        assertEquals(List.of(id(1), id(2)), first.items().stream().map(i -> i.productId()).toList());
        assertTrue(first.next().isPresent());
        ModerationQueuePage second = service.queue(2, first.next());
        assertEquals(List.of(id(3), id(4)), second.items().stream().map(i -> i.productId()).toList());
        ModerationQueuePage third = service.queue(2, second.next());
        assertEquals(List.of(id(5)), third.items().stream().map(i -> i.productId()).toList());
        assertTrue(third.next().isEmpty());
        assertNotNull(third.at());
    }
}
