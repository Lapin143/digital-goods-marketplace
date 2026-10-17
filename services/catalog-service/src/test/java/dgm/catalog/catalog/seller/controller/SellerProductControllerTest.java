package dgm.catalog.catalog.seller.controller;

import static dgm.catalog.catalog.seller.Products.OTHER_SELLER;
import static dgm.catalog.catalog.seller.Products.REVIEW;
import static dgm.catalog.catalog.seller.Products.SELLER;
import static dgm.catalog.catalog.seller.Products.T0;
import static dgm.catalog.catalog.seller.Products.form;
import static dgm.catalog.catalog.seller.Products.id;
import static dgm.catalog.catalog.seller.Products.in;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.catalog.FakeCatalogTransaction;
import dgm.catalog.catalog.cache.MemoryCache;
import dgm.catalog.catalog.domain.ProductSettings;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.seller.FakeApprovedSellers;
import dgm.catalog.catalog.seller.FakeSellerProducts;
import dgm.catalog.catalog.seller.service.SellerProductService;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.kit.time.ManualClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Семь операций кабинета по схемам ProductInput, SellerProduct и SellerProductPage: разбор тела, заголовки, ETag, If-Match и ошибки. */
class SellerProductControllerTest {

    private static final String BASE = "/api/v1/seller/products";
    private static final String INPUT = "{\"title\":\"Лунная тропа: ключ Steam\",\"description\":\"Приключенческая игра, ключ активации Steam\","
            + "\"productType\":\"game_key\",\"platform\":\"Steam\",\"activationRegions\":[\"RU\",\"KZ\"],"
            + "\"price\":{\"amount\":149900,\"currency\":\"RUB\"},\"issuanceMethod\":\"key_pool\"}";
    private static final Set<String> PRODUCT_FIELDS = Set.of("productId", "sellerId", "title", "description", "productType", "platform",
            "activationRegions", "price", "issuanceMethod", "status", "rejectionReason", "blockReason", "submittedAt", "reviewDeadlineAt", "version",
            "createdAt", "updatedAt");

    private ManualClock clock;
    private FakeSellerProducts products;
    private FakeCatalogTransaction transaction;
    private final MemoryCache memory = new MemoryCache();
    private SellerProductController controller;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        products = new FakeSellerProducts();
        transaction = new FakeCatalogTransaction();
        FakeApprovedSellers sellers = new FakeApprovedSellers().approve(SELLER).approve(OTHER_SELLER);
        controller = new SellerProductController(new SellerProductService(products, transaction, sellers, clock, new ProductSettings(REVIEW), memory.cache()));
    }

    private static MockHttpServletRequest request(String method, String uri, UUID seller) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser("0199e0a0-0000-4000-8000-0000000000a1", Set.of("seller.catalog"),
                Set.of("seller"), List.of("pwd", "otp"), true, seller == null ? null : seller.toString()));
        return request;
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return request(method, uri, SELLER);
    }

    private static MockHttpServletRequest json(String method, String uri, String body) {
        MockHttpServletRequest request = request(method, uri);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static Map<String, Object> body(ResponseEntity<String> response) {
        return Json.parseObject(response.getBody());
    }

    private ResponseEntity<String> create(String input) throws IOException {
        return controller.create(json("POST", BASE, input));
    }

    private ResponseEntity<String> replace(UUID productId, String ifMatch, String input) throws IOException {
        MockHttpServletRequest request = json("PUT", BASE + "/" + productId, input);
        if (ifMatch != null) {
            request.addHeader("If-Match", ifMatch);
        }
        return controller.replace(productId.toString(), request);
    }

    private static ProblemException refused(ThrowingCall call) {
        return assertThrows(ProblemException.class, call::run);
    }

    private static List<Map<String, Object>> errors(ProblemException e) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        return errors;
    }

    private static List<String> pointers(ProblemException e) {
        return errors(e).stream().map(m -> (String) m.get("pointer")).toList();
    }

    private interface ThrowingCall {
        void run() throws IOException;
    }

    // ------------------------------------------------------------------ создание

    @Test
    void createAnswers201WithEtagLocationNoStoreAndTheFullSchema() throws IOException {
        ResponseEntity<String> response = create(INPUT);
        assertEquals(201, response.getStatusCode().value());
        Map<String, Object> product = body(response);
        assertEquals(PRODUCT_FIELDS, product.keySet());
        assertEquals("draft", product.get("status"));
        assertEquals(1, product.get("version"));
        assertEquals(SELLER.toString(), product.get("sellerId"));
        assertEquals(Map.of("amount", 149900, "currency", "RUB"), product.get("price"));
        assertEquals(List.of("RU", "KZ"), product.get("activationRegions"));
        assertNull(product.get("rejectionReason"));
        assertNull(product.get("blockReason"));
        assertNull(product.get("submittedAt"));
        assertNull(product.get("reviewDeadlineAt"));
        assertEquals("\"1\"", response.getHeaders().getETag());
        assertEquals(BASE + "/" + product.get("productId"), response.getHeaders().getFirst("Location"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("application/json;charset=UTF-8", response.getHeaders().getContentType().toString());
        assertEquals(1, transaction.published("product.created").size());
    }

    @Test
    void createWithEmptyRegionListMeansAllRegions() throws IOException {
        ResponseEntity<String> response = create(INPUT.replace("[\"RU\",\"KZ\"]", "[]"));
        assertEquals(201, response.getStatusCode().value());
        assertEquals(List.of(), body(response).get("activationRegions"));
    }

    @Test
    void createTrimsTextFields() throws IOException {
        ResponseEntity<String> response = create(INPUT.replace("\"Steam\"", "\"  Steam  \""));
        assertEquals("Steam", body(response).get("platform"));
    }

    @Test
    void createRefusesTheMissingSellerIdInTheTokenAs403() {
        MockHttpServletRequest request = request("POST", BASE, null);
        request.setContentType("application/json");
        request.setContent(INPUT.getBytes(StandardCharsets.UTF_8));
        ProblemException e = refused(() -> controller.create(request));
        assertEquals(ProblemType.FORBIDDEN, e.type());
        assertEquals(0, products.count());
    }

    @Test
    void createRefusesAnUnapprovedSeller() {
        UUID stranger = UUID.fromString("0199e0a0-0000-7000-8000-000000000999");
        MockHttpServletRequest request = request("POST", BASE, stranger);
        request.setContentType("application/json");
        request.setContent(INPUT.getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.FORBIDDEN, refused(() -> controller.create(request)).type());
    }

    @Test
    void createRefusesWrongMediaTypeAndBrokenJson() {
        MockHttpServletRequest text = request("POST", BASE);
        text.setContentType("text/plain");
        text.setContent(INPUT.getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, refused(() -> controller.create(text)).type());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, refused(() -> controller.create(request("POST", BASE))).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> create("{")).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> create("[]")).type());
    }

    @Test
    void createListsEveryViolationOfAnEmptyObject() {
        ProblemException e = refused(() -> create("{}"));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(List.of("/title", "/description", "/productType", "/platform", "/activationRegions", "/price/amount", "/issuanceMethod"),
                pointers(e));
        assertTrue(errors(e).stream().allMatch(m -> "required".equals(m.get("code"))));
    }

    @Test
    void createNamesWrongTypesUnknownValuesAndExtraFields() {
        String bad = "{\"title\":5,\"description\":\"d\",\"productType\":\"toy\",\"platform\":\"Steam\",\"activationRegions\":\"RU\","
                + "\"price\":{\"amount\":1.5,\"currency\":\"USD\",\"extra\":1},\"issuanceMethod\":\"post\",\"color\":\"red\"}";
        ProblemException e = refused(() -> create(bad));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        Map<String, String> codes = new java.util.LinkedHashMap<>();
        errors(e).forEach(m -> codes.put((String) m.get("pointer"), (String) m.get("code")));
        assertEquals("unknown_value", codes.get("/color"));
        assertEquals("invalid_format", codes.get("/title"));
        assertEquals("unknown_value", codes.get("/productType"));
        assertEquals("invalid_format", codes.get("/activationRegions"));
        assertEquals("unknown_value", codes.get("/price/extra"));
        assertEquals("unknown_value", codes.get("/price/currency"));
        assertEquals("invalid_format", codes.get("/price/amount"));
        assertEquals("unknown_value", codes.get("/issuanceMethod"));
        assertEquals(errors(e).size(), codes.size(), "каждое поле названо один раз");
    }

    @Test
    void createRefusesSellerApiPriceZeroDuplicatesAndLongTitle() {
        assertEquals(List.of("/issuanceMethod"), pointers(refused(() -> create(INPUT.replace("\"key_pool\"", "\"seller_api\"")))));
        assertEquals(List.of("/price/amount"), pointers(refused(() -> create(INPUT.replace("149900", "0")))));
        assertEquals(List.of("/price/amount"), pointers(refused(() -> create(INPUT.replace("149900", "9007199254740992")))));
        assertEquals(List.of("/price/amount"), pointers(refused(() -> create(INPUT.replace("149900", "123456789012345678901234567890")))));
        assertEquals(List.of("/activationRegions/1"), pointers(refused(() -> create(INPUT.replace("\"KZ\"", "\"RU\"")))));
        assertEquals(List.of("/activationRegions/0"), pointers(refused(() -> create(INPUT.replace("\"RU\"", "\"ru\"")))));
        assertEquals(List.of("/title"), pointers(refused(() -> create(INPUT.replace("Лунная тропа: ключ Steam", "а".repeat(201))))));
        assertEquals(List.of("/title"), pointers(refused(() -> create(INPUT.replace("Лунная тропа: ключ Steam", "   ")))));
        assertEquals(0, products.count());
    }

    @Test
    void createRefusesNullFieldsAsMissing() {
        ProblemException e = refused(() -> create(INPUT.replace("\"platform\":\"Steam\"", "\"platform\":null")));
        assertEquals(List.of("/platform"), pointers(e));
        assertEquals("required", errors(e).get(0).get("code"));
    }

    @Test
    void createRefusesATooLargeBody() {
        String huge = INPUT.replace("Приключенческая игра, ключ активации Steam", "я".repeat(ProductForms.MAX_BODY));
        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, refused(() -> create(huge)).type());
    }

    // ------------------------------------------------------------------ чтение

    @Test
    void getReturnsTheProductWithEtagAndForeignIsNotFound() throws IOException {
        UUID productId = UUID.fromString((String) body(create(INPUT)).get("productId"));
        ResponseEntity<String> response = controller.get(productId.toString(), request("GET", BASE + "/" + productId));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("\"1\"", response.getHeaders().getETag());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(PRODUCT_FIELDS, body(response).keySet());
        ProblemException foreign = refused(() -> controller.get(productId.toString(), request("GET", BASE + "/" + productId, OTHER_SELLER)));
        assertEquals(ProblemType.NOT_FOUND, foreign.type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> controller.get(id(77).toString(), request("GET", BASE + "/x"))).type());
    }

    @Test
    void anIdThatIsNotALowercaseUuidIs400() {
        for (String bad : new String[] {"abc", "0199E0A0-0000-7000-8000-000000000201", "0199e0a0-0000-7000-8000-00000000020"}) {
            assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.get(bad, request("GET", BASE + "/x"))).type(), bad);
            assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.submit(bad, request("POST", BASE + "/x/submit"))).type(), bad);
        }
    }

    @Test
    void listPagesWithACursorAndFiltersByStatus() throws IOException {
        for (int i = 1; i <= 3; i++) {
            products.with(in(id(i), SELLER, form(), i == 2 ? ProductStatus.REJECTED : ProductStatus.DRAFT, 1, T0.plusSeconds(i)));
        }
        MockHttpServletRequest first = request("GET", BASE);
        first.setParameter("limit", "2");
        Map<String, Object> page = body(controller.list(first));
        assertEquals(2, ((List<?>) page.get("items")).size());
        Map<?, ?> info = (Map<?, ?>) page.get("page");
        assertEquals(2, info.get("limit"));
        String cursor = (String) info.get("nextCursor");
        assertTrue(cursor != null && !cursor.isEmpty());

        MockHttpServletRequest second = request("GET", BASE);
        second.setParameter("limit", "2");
        second.setParameter("cursor", cursor);
        Map<String, Object> rest = body(controller.list(second));
        assertEquals(1, ((List<?>) rest.get("items")).size());
        assertNull(((Map<?, ?>) rest.get("page")).get("nextCursor"));

        MockHttpServletRequest filtered = request("GET", BASE);
        filtered.setParameter("status", "rejected,archived");
        Map<String, Object> only = body(controller.list(filtered));
        assertEquals(1, ((List<?>) only.get("items")).size());
        assertEquals(id(2).toString(), ((Map<?, ?>) ((List<?>) only.get("items")).get(0)).get("productId"));
    }

    @Test
    void listRefusesBadParametersTogether() {
        MockHttpServletRequest request = request("GET", BASE);
        request.setParameter("status", "draft,unknown");
        request.setParameter("limit", "0");
        request.setParameter("cursor", "не-курсор");
        request.setParameter("sort", "price");
        ProblemException e = refused(() -> controller.list(request));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        List<String> parameters = errors(e).stream().map(m -> (String) m.get("parameter")).sorted().toList();
        assertEquals(List.of("cursor", "limit", "sort", "status"), parameters);
    }

    // ------------------------------------------------------------------ правка

    @Test
    void replaceNeedsIfMatchAndAStrongEtag() throws IOException {
        UUID productId = UUID.fromString((String) body(create(INPUT)).get("productId"));
        assertEquals(ProblemType.PRECONDITION_REQUIRED, refused(() -> replace(productId, null, INPUT)).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> replace(productId, "1", INPUT)).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> replace(productId, "W/\"1\"", INPUT)).type());
    }

    @Test
    void replaceSavesWithTheNewEtagAndRefusesAStaleOne() throws IOException {
        UUID productId = UUID.fromString((String) body(create(INPUT)).get("productId"));
        ResponseEntity<String> saved = replace(productId, "\"1\"", INPUT.replace("149900", "99900"));
        assertEquals(200, saved.getStatusCode().value());
        assertEquals("\"2\"", saved.getHeaders().getETag());
        assertEquals(Map.of("amount", 99900, "currency", "RUB"), body(saved).get("price"));
        ProblemException stale = refused(() -> replace(productId, "\"1\"", INPUT.replace("149900", "88800")));
        assertEquals(ProblemType.VERSION_CONFLICT, stale.type());
    }

    @Test
    void replaceCheckpointsOrderTheBodyBeforeTheLookup() throws IOException {
        UUID productId = UUID.fromString((String) body(create(INPUT)).get("productId"));
        assertEquals(ProblemType.VALIDATION_FAILED, refused(() -> replace(productId, "\"1\"", "{}")).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> replace(id(77), "\"1\"", INPUT)).type());
    }

    @Test
    void replaceOfAPublishedProductShowsTheNewStatusInTheAnswer() throws IOException {
        products.with(in(ProductStatus.PUBLISHED, 3));
        Map<String, Object> changed = body(replace(id(1), "\"3\"", INPUT.replace("Лунная тропа: ключ Steam", "Лунная тропа 2")));
        assertEquals("on_moderation", changed.get("status"));
        assertEquals(4, changed.get("version"));
        assertEquals("2026-10-06T12:00:00.000Z", changed.get("reviewDeadlineAt"));
    }

    // ------------------------------------------------------------------ отправка, архив, восстановление

    @Test
    void submitArchiveAndRestoreAnswer200WithTheNewStateAndEtag() throws IOException {
        UUID productId = UUID.fromString((String) body(create(INPUT)).get("productId"));
        String url = BASE + "/" + productId;
        ResponseEntity<String> submitted = controller.submit(productId.toString(), request("POST", url + "/submit"));
        assertEquals("on_moderation", body(submitted).get("status"));
        assertEquals("\"2\"", submitted.getHeaders().getETag());
        assertEquals("2026-10-03T12:00:00.000Z", body(submitted).get("submittedAt"));
        assertEquals("2026-10-06T12:00:00.000Z", body(submitted).get("reviewDeadlineAt"));
        ResponseEntity<String> archived = controller.archive(productId.toString(), request("POST", url + "/archive"));
        assertEquals("archived", body(archived).get("status"));
        assertNull(body(archived).get("submittedAt"));
        ResponseEntity<String> restored = controller.restore(productId.toString(), request("POST", url + "/restore"));
        assertEquals("draft", body(restored).get("status"));
        assertEquals("\"4\"", restored.getHeaders().getETag());
    }

    @Test
    void forbiddenTransitionsAre409WithTheCurrentStatus() throws IOException {
        UUID productId = UUID.fromString((String) body(create(INPUT)).get("productId"));
        String url = BASE + "/" + productId;
        ProblemException restore = refused(() -> controller.restore(productId.toString(), request("POST", url + "/restore")));
        assertEquals(ProblemType.STATE_CONFLICT, restore.type());
        assertEquals("draft", restore.extensions().get("currentStatus"));
        controller.submit(productId.toString(), request("POST", url + "/submit"));
        ProblemException again = refused(() -> controller.submit(productId.toString(), request("POST", url + "/submit")));
        assertEquals("on_moderation", again.extensions().get("currentStatus"));
    }

    @Test
    void blockedProductShowsTheModeratorNoteAsBlockReason() throws IOException {
        products.with(in(ProductStatus.BLOCKED, 4));
        Map<String, Object> blocked = body(controller.get(id(1).toString(), request("GET", BASE + "/x")));
        assertEquals("blocked", blocked.get("status"));
        assertEquals("В описании чужой товарный знак.", blocked.get("blockReason"));
        assertNull(blocked.get("rejectionReason"));
    }

    @Test
    void rejectedProductShowsTheRejectionReason() throws IOException {
        products.with(in(ProductStatus.REJECTED, 2));
        Map<String, Object> rejected = body(controller.get(id(1).toString(), request("GET", BASE + "/x")));
        assertEquals("Описание не соответствует товару.", rejected.get("rejectionReason"));
        assertNull(rejected.get("blockReason"));
    }
}
