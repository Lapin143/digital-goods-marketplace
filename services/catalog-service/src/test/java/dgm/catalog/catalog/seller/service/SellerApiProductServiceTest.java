package dgm.catalog.catalog.seller.service;

import static dgm.catalog.catalog.seller.Products.OTHER_SELLER;
import static dgm.catalog.catalog.seller.Products.REVIEW;
import static dgm.catalog.catalog.seller.Products.SELLER;
import static dgm.catalog.catalog.seller.Products.T0;
import static dgm.catalog.catalog.seller.Products.form;
import static dgm.catalog.catalog.seller.Products.id;
import static dgm.catalog.catalog.seller.Products.in;
import static dgm.catalog.catalog.seller.Products.withIssuance;
import static dgm.catalog.catalog.seller.Products.withPrice;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.catalog.catalog.FakeCatalogTransaction;
import dgm.catalog.catalog.cache.MemoryCache;
import dgm.catalog.catalog.domain.IssuanceMethod;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductForm;
import dgm.catalog.catalog.domain.ProductSettings;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.seller.FakeApprovedSellers;
import dgm.catalog.catalog.seller.FakeSellerIntegrations;
import dgm.catalog.catalog.seller.FakeSellerProducts;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Товар с выдачей по API в кабинете продавца (R2, Р-184, Р-187): создание без подключения, запрет смены способа, подача на модерацию
 * только при действующем подключении системы. Подключение проверяется до транзакции каталога и только там, где статус вообще допускает подачу.
 */
@Tag("TC-330")
@Tag("TC-331")
@Tag("TC-332")
class SellerApiProductServiceTest {

    private static final ProductForm API = withIssuance(form(), IssuanceMethod.SELLER_API);

    private ManualClock clock;
    private FakeSellerProducts products;
    private FakeCatalogTransaction transaction;
    private FakeApprovedSellers sellers;
    private FakeSellerIntegrations integrations;
    private SellerProductService service;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        products = new FakeSellerProducts();
        transaction = new FakeCatalogTransaction();
        sellers = new FakeApprovedSellers().approve(SELLER).approve(OTHER_SELLER);
        integrations = new FakeSellerIntegrations();
        service = new SellerProductService(products, transaction, sellers, integrations, clock, new ProductSettings(REVIEW), new MemoryCache().cache());
    }

    private static ProblemType problem(Runnable action) {
        return assertThrows(ProblemException.class, action::run).type();
    }

    private static Product apiProduct(int n, ProductStatus status) {
        return in(id(n), SELLER, API, status, 3, T0);
    }

    // ------------------------------------------------------------------ создание

    @Test
    void createStoresAnApiDraftAndTheCreatedEventNamesTheMethod() {
        Product p = service.create(SELLER, API);
        assertEquals(ProductStatus.DRAFT, p.status());
        assertEquals(IssuanceMethod.SELLER_API, products.row(p.id()).form().issuanceMethod());
        Map<String, Object> data = transaction.published("product.created").get(0).data();
        assertEquals("seller_api", data.get("issuanceMethod"));
        assertEquals("draft", data.get("status"));
    }

    @Test
    void creatingAndEditingADraftNeverAsksForTheIntegration() {
        Product p = service.create(SELLER, API);
        service.replace(SELLER, p.id(), 1, withPrice(API, 129_900L));
        assertEquals(0, integrations.checks());
    }

    // ------------------------------------------------------------------ способ выдачи не меняется

    @Test
    void replaceRefusesAnotherMethodAndWritesNothing() {
        Product pool = service.create(SELLER, form());
        Product api = service.create(SELLER, API);
        int events = transaction.published().size();

        assertEquals(ProblemType.VALIDATION_FAILED, problem(() -> service.replace(SELLER, pool.id(), 1, API)));
        assertEquals(ProblemType.VALIDATION_FAILED, problem(() -> service.replace(SELLER, api.id(), 1, form())));
        assertEquals(1, products.row(pool.id()).version());
        assertEquals(IssuanceMethod.KEY_POOL, products.row(pool.id()).form().issuanceMethod());
        assertEquals(IssuanceMethod.SELLER_API, products.row(api.id()).form().issuanceMethod());
        assertEquals(events, transaction.published().size(), "отказ не пишет событий");
    }

    @Test
    void aStaleVersionIsReportedBeforeTheMethodIsChecked() {
        Product api = service.create(SELLER, API);
        assertEquals(ProblemType.VERSION_CONFLICT, problem(() -> service.replace(SELLER, api.id(), 5, form())));
    }

    // ------------------------------------------------------------------ подача на модерацию

    @Test
    void submitWithoutAnIntegrationIsRefusedAndNothingIsWritten() {
        Product api = service.create(SELLER, API);
        int events = transaction.published().size();
        assertEquals(ProblemType.INTEGRATION_REQUIRED, problem(() -> service.submit(SELLER, api.id())));
        assertEquals(ProductStatus.DRAFT, products.row(api.id()).status());
        assertEquals(1, products.row(api.id()).version());
        assertEquals(events, transaction.published().size());
        assertEquals(1, integrations.checks());
    }

    @Test
    void submitWithAnActiveIntegrationQueuesTheProduct() {
        Product api = service.create(SELLER, API);
        integrations.connect(SELLER);
        Product submitted = service.submit(SELLER, api.id());
        assertEquals(ProductStatus.ON_MODERATION, submitted.status());
        assertEquals(T0.plus(REVIEW), submitted.reviewDeadlineAt());
    }

    @Test
    void aDisconnectedSystemIsRefusedAgainAndOnlyTheOwnerSystemCounts() {
        Product api = service.create(SELLER, API);
        integrations.connect(OTHER_SELLER);
        assertEquals(ProblemType.INTEGRATION_REQUIRED, problem(() -> service.submit(SELLER, api.id())), "подключение другого продавца не считается");
        integrations.connect(SELLER).disconnect(SELLER);
        assertEquals(ProblemType.INTEGRATION_REQUIRED, problem(() -> service.submit(SELLER, api.id())));
    }

    @Test
    void aRejectedAndAModeratorBlockedApiProductNeedTheIntegrationToo() {
        products.with(apiProduct(1, ProductStatus.REJECTED), apiProduct(2, ProductStatus.BLOCKED));
        assertEquals(ProblemType.INTEGRATION_REQUIRED, problem(() -> service.submit(SELLER, id(1))));
        assertEquals(ProblemType.INTEGRATION_REQUIRED, problem(() -> service.submit(SELLER, id(2))));
        integrations.connect(SELLER);
        assertEquals(ProductStatus.ON_MODERATION, service.submit(SELLER, id(1)).status());
        assertEquals(ProductStatus.ON_MODERATION, service.submit(SELLER, id(2)).status());
    }

    @Test
    void aStatusThatDoesNotAllowSubmissionGivesStateConflictAndNeverAsksForTheIntegration() {
        products.with(apiProduct(1, ProductStatus.ON_MODERATION), apiProduct(2, ProductStatus.ARCHIVED), apiProduct(3, ProductStatus.PUBLISHED));
        for (int n = 1; n <= 3; n++) {
            int product = n;
            assertEquals(ProblemType.STATE_CONFLICT, problem(() -> service.submit(SELLER, id(product))));
        }
        assertEquals(0, integrations.checks());
    }

    @Test
    void aKeyPoolProductNeverAsksForTheIntegration() {
        Product pool = service.create(SELLER, form());
        assertEquals(ProductStatus.ON_MODERATION, service.submit(SELLER, pool.id()).status());
        assertEquals(0, integrations.checks());
    }

    @Test
    void aMissingForeignOrUnapprovedCaseIsRefusedFirstAndNeverAsksForTheIntegration() {
        Product api = service.create(SELLER, API);
        assertEquals(ProblemType.NOT_FOUND, problem(() -> service.submit(SELLER, id(99))));
        assertEquals(ProblemType.NOT_FOUND, problem(() -> service.submit(OTHER_SELLER, api.id())));
        sellers.revoke(SELLER);
        assertEquals(ProblemType.FORBIDDEN, problem(() -> service.submit(SELLER, api.id())));
        assertEquals(0, integrations.checks());
    }

    @Test
    void theIntegrationIsAskedOutsideTheCatalogTransaction() {
        Product api = service.create(SELLER, API);
        int executions = transaction.executions();
        integrations.connect(SELLER).onCheck(() -> assertEquals(executions, transaction.executions(), "транзакция каталога ещё не началась"));
        service.submit(SELLER, api.id());
        assertEquals(1, integrations.checks());
    }

    @Test
    void anotherSellersApiProductIsNotTouchedByMyIntegration() {
        UUID foreign = id(7);
        products.with(in(foreign, OTHER_SELLER, API, ProductStatus.DRAFT, 1, T0));
        integrations.connect(SELLER);
        assertEquals(ProblemType.NOT_FOUND, problem(() -> service.submit(SELLER, foreign)));
        assertEquals(List.of(), transaction.published());
    }
}
