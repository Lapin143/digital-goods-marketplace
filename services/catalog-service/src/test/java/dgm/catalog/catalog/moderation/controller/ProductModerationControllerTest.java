package dgm.catalog.catalog.moderation.controller;

import static dgm.catalog.catalog.seller.Products.REVIEW;
import static dgm.catalog.catalog.seller.Products.SELLER;
import static dgm.catalog.catalog.seller.Products.T0;
import static dgm.catalog.catalog.seller.Products.form;
import static dgm.catalog.catalog.seller.Products.id;
import static dgm.catalog.catalog.seller.Products.in;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.catalog.FakeCatalogTransaction;
import dgm.catalog.catalog.cache.MemoryCache;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.moderation.service.ProductModerationService;
import dgm.catalog.catalog.seller.FakeSellerProducts;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.kit.time.ManualClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Пять операций модератора по схемам SellerProduct и ModerationQueuePage: заголовки, тело решения, параметры очереди и ошибки. */
class ProductModerationControllerTest {

    private static final String BASE = "/api/v1/staff/products";
    private static final String MODERATOR_ID = "5d3b1f79-8c2e-4a60-b4d8-6e0a2c4f1b37";
    private static final Set<String> PRODUCT_FIELDS = Set.of("productId", "sellerId", "title", "description", "productType", "platform",
            "activationRegions", "price", "issuanceMethod", "status", "rejectionReason", "blockReason", "submittedAt", "reviewDeadlineAt", "version",
            "createdAt", "updatedAt");
    private static final Set<String> QUEUE_FIELDS = Set.of("productId", "sellerId", "title", "productType", "price", "submittedAt", "reviewDeadlineAt",
            "overdue");

    private ManualClock clock;
    private FakeSellerProducts products;
    private FakeCatalogTransaction transaction;
    private ProductModerationController controller;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0.plus(Duration.ofHours(1)));
        products = new FakeSellerProducts();
        transaction = new FakeCatalogTransaction();
        controller = new ProductModerationController(new ProductModerationService(products, transaction, new MemoryCache().cache(), clock));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser(MODERATOR_ID, Set.of("staff.moderation"), Set.of("moderator"),
                List.of("pwd", "otp"), true));
        return request;
    }

    private static MockHttpServletRequest json(String uri, String body) {
        MockHttpServletRequest request = request("POST", uri);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static Map<String, Object> body(ResponseEntity<String> response) {
        return Json.parseObject(response.getBody());
    }

    private static Product queued(int n, Instant submittedAt) {
        Product base = in(id(n), SELLER, form(), ProductStatus.ON_MODERATION, 2, T0);
        return new Product(base.id(), base.sellerId(), base.form(), null, null, null, null, submittedAt, submittedAt.plus(REVIEW), null, null,
                ProductStatus.ON_MODERATION, 2, T0, T0);
    }

    private static ProblemException refused(ThrowingCall call) {
        return assertThrows(ProblemException.class, call::run);
    }

    private interface ThrowingCall {
        void run() throws IOException;
    }

    private static List<String> pointers(ProblemException e) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        return errors.stream().map(m -> (String) m.get("pointer")).toList();
    }

    // ---------------------------------------------------------------- очередь

    @Test
    void theQueueMatchesTheSchemaAndIsNotCached() {
        products.with(queued(1, T0), queued(2, T0.plusSeconds(60)));
        ResponseEntity<String> response = controller.queue(request("GET", BASE));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> page = body(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertEquals(2, items.size());
        assertEquals(QUEUE_FIELDS, items.get(0).keySet());
        assertEquals(id(1).toString(), items.get(0).get("productId"));
        assertEquals(Map.of("amount", 149900, "currency", "RUB"), items.get(0).get("price"));
        assertFalse((Boolean) items.get(0).get("overdue"));
        assertEquals(Map.of("limit", 20, "nextCursor", null), page.get("page"));
    }

    @Test
    void theQueueReturnsACursorAndAcceptsItBack() {
        for (int n = 1; n <= 3; n++) {
            products.with(queued(n, T0.plusSeconds(n * 10L)));
        }
        MockHttpServletRequest first = request("GET", BASE);
        first.setParameter("limit", "2");
        Map<String, Object> firstPage = body(controller.queue(first));
        @SuppressWarnings("unchecked")
        String cursor = (String) ((Map<String, Object>) firstPage.get("page")).get("nextCursor");
        assertTrue(cursor != null && !cursor.isBlank());
        MockHttpServletRequest second = request("GET", BASE);
        second.setParameter("limit", "2");
        second.setParameter("cursor", cursor);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body(controller.queue(second)).get("items");
        assertEquals(List.of(id(3).toString()), items.stream().map(i -> i.get("productId")).toList());
    }

    @Test
    void aBrokenCursorIsAValidationError() {
        MockHttpServletRequest request = request("GET", BASE);
        request.setParameter("cursor", "не-курсор");
        assertEquals(ProblemType.VALIDATION_FAILED, refused(() -> controller.queue(request)).type());
    }

    // ---------------------------------------------------------------- карточка

    @Test
    void theCardShowsTheWholeProductAndCarriesTheVersionAsEtag() {
        products.with(queued(1, T0));
        ResponseEntity<String> response = controller.card(id(1).toString());
        assertEquals(PRODUCT_FIELDS, body(response).keySet());
        assertEquals("on_moderation", body(response).get("status"));
        assertEquals("\"2\"", response.getHeaders().getETag());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void theCardOfADraftAndOfAnUnknownProductIsNotFound() {
        products.with(in(ProductStatus.DRAFT, 1));
        assertEquals(ProblemType.NOT_FOUND, refused(() -> controller.card(id(1).toString())).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> controller.card(id(9).toString())).type());
    }

    @Test
    void aBadIdentifierIsABadRequestBeforeAnyLookup() {
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.card("not-a-uuid")).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.card(id(1).toString().toUpperCase())).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.approve("x", request("POST", BASE + "/x/approve"))).type());
    }

    // ---------------------------------------------------------------- решения

    @Test
    void approveReturnsThePublishedProduct() {
        products.with(queued(1, T0));
        ResponseEntity<String> response = controller.approve(id(1).toString(), request("POST", BASE + "/" + id(1) + "/approve"));
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> product = body(response);
        assertEquals("published", product.get("status"));
        assertEquals(3, product.get("version"));
        assertNull(product.get("submittedAt"));
        assertEquals("\"3\"", response.getHeaders().getETag());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> audit = transaction.published("audit.recorded").get(0).data();
        assertEquals(MODERATOR_ID, audit.get("actorId"));
        assertEquals("203.0.113.7", audit.get("actorIp"), "адрес берётся из заголовка шлюза");
    }

    @Test
    void rejectTakesTheReasonFromTheBodyAndTrimsIt() throws IOException {
        products.with(queued(1, T0));
        ResponseEntity<String> response = controller.reject(id(1).toString(),
                json(BASE + "/" + id(1) + "/reject", "{\"reason\":\"  В описании есть запрещённое содержимое  \"}"));
        Map<String, Object> product = body(response);
        assertEquals("rejected", product.get("status"));
        assertEquals("В описании есть запрещённое содержимое", product.get("rejectionReason"));
    }

    @Test
    void blockTakesTheReasonFromTheBodyAndShowsItToTheSellerAsBlockReason() throws IOException {
        products.with(in(ProductStatus.PUBLISHED, 3));
        ResponseEntity<String> response = controller.block(id(1).toString(), json(BASE + "/" + id(1) + "/block", "{\"reason\":\"Жалоба правообладателя\"}"));
        Map<String, Object> product = body(response);
        assertEquals("blocked", product.get("status"));
        assertEquals("Жалоба правообладателя", product.get("blockReason"));
    }

    @Test
    void theReasonIsRequiredNonEmptyAtMostAThousandCharactersAndTheOnlyField() throws IOException {
        products.with(queued(1, T0));
        String uri = BASE + "/" + id(1) + "/reject";
        String thousandOne = "ж".repeat(1001);
        assertEquals(List.of("/reason"), pointers(refused(() -> controller.reject(id(1).toString(), json(uri, "{}")))));
        assertEquals(List.of("/reason"), pointers(refused(() -> controller.reject(id(1).toString(), json(uri, "{\"reason\":\"   \"}")))));
        assertEquals(List.of("/reason"), pointers(refused(() -> controller.reject(id(1).toString(), json(uri, "{\"reason\":42}")))));
        assertEquals(List.of("/reason"), pointers(refused(() -> controller.reject(id(1).toString(), json(uri, "{\"reason\":\"" + thousandOne + "\"}")))));
        assertEquals(List.of("/comment"), pointers(refused(() -> controller.reject(id(1).toString(), json(uri, "{\"reason\":\"ок\",\"comment\":\"x\"}")))));
        assertEquals(ProductStatus.ON_MODERATION, products.row(id(1)).status(), "ошибка тела решение не принимает");
        controller.reject(id(1).toString(), json(uri, "{\"reason\":\"" + "ж".repeat(1000) + "\"}"));
        assertEquals(ProductStatus.REJECTED, products.row(id(1)).status());
    }

    @Test
    void theBodyMustBeJsonWithTheRightContentType() {
        products.with(queued(1, T0));
        MockHttpServletRequest text = request("POST", BASE + "/" + id(1) + "/reject");
        text.setContentType("text/plain");
        text.setContent("{\"reason\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, refused(() -> controller.reject(id(1).toString(), text)).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.reject(id(1).toString(), json(BASE + "/" + id(1) + "/reject", "это не JSON"))).type());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, refused(() -> controller.block(id(1).toString(), request("POST", BASE + "/" + id(1) + "/block"))).type());
    }

    @Test
    void aBadIdentifierInThePathIsRefusedBeforeTheBodyIsRead() {
        MockHttpServletRequest broken = json(BASE + "/x/reject", "это не JSON");
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.reject("x", broken)).type());
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void aRepeatedDecisionIsAStateConflict() throws IOException {
        products.with(queued(1, T0));
        controller.approve(id(1).toString(), request("POST", BASE + "/" + id(1) + "/approve"));
        ProblemException e = refused(() -> controller.reject(id(1).toString(), json(BASE + "/" + id(1) + "/reject", "{\"reason\":\"Поздно\"}")));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("published", e.extensions().get("currentStatus"));
    }
}
