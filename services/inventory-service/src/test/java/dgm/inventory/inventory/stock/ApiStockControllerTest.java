package dgm.inventory.inventory.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.registry.ProductCopy;
import dgm.inventory.inventory.reservation.FakeStock;
import dgm.inventory.inventory.service.StockPublisher;
import dgm.inventory.inventory.stock.controller.ApiStockController;
import dgm.inventory.inventory.stock.domain.ApiStock;
import dgm.inventory.inventory.stock.service.ApiStockService;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Операция {@code setApiStock} по схемам ApiStockRequest и ApiStock: заголовки, разбор тела, порядок проверок, ошибки (US-4.7, ADR-008). */
class ApiStockControllerTest {

    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-0000000002a2");
    private static final String URI = "/api/v1/seller/products/" + PRODUCT + "/api-stock";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T12:00:00.123Z"), ZoneOffset.UTC);

    private final FakeStock.Products products = new FakeStock.Products();
    private final FakeStock.Tx transaction = new FakeStock.Tx();
    private final FakeApiStock stock = new FakeApiStock();
    private ApiStockController controller;

    @BeforeEach
    void setUp() {
        products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "seller_api", "published", 1));
        stock.rows.put(PRODUCT, new ApiStock(PRODUCT, 10, 3, Instant.parse("2026-11-05T08:00:00Z")));
        controller = new ApiStockController(new ApiStockService(products, stock, new StockPublisher(products, transaction, CLOCK), transaction, CLOCK));
    }

    private static MockHttpServletRequest request(UUID seller, String ifMatch, String contentType, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", URI);
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser("0199e0a0-0000-4000-8000-0000000000a1", Set.of("seller.catalog"),
                Set.of("seller"), List.of("pwd", "otp"), true, seller == null ? null : seller.toString()));
        if (ifMatch != null) {
            request.addHeader("If-Match", ifMatch);
        }
        if (contentType != null) {
            request.setContentType(contentType);
        }
        if (body != null) {
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    private static MockHttpServletRequest put(String body) {
        return request(SELLER, "\"3\"", "application/json", body);
    }

    private interface ThrowingCall {
        void run() throws IOException;
    }

    private static ProblemException refused(ThrowingCall call) {
        return assertThrows(ProblemException.class, call::run);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ProblemException e) {
        return (List<Map<String, Object>>) e.extensions().get("errors");
    }

    @Tag("TC-419")
    @Test
    void theAnswerMatchesTheSchemaCarriesTheNewETagAndIsNotCached() throws IOException {
        ResponseEntity<String> response = controller.set(PRODUCT.toString(), put("{\"quantity\":25}"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("\"4\"", response.getHeaders().getETag());
        assertTrue(response.getHeaders().getContentType().toString().startsWith("application/json"));
        Map<String, Object> body = Json.parseObject(response.getBody());
        assertEquals(Set.of("productId", "quantity", "version", "updatedAt"), body.keySet());
        assertEquals(PRODUCT.toString(), body.get("productId"));
        assertEquals(25, body.get("quantity"));
        assertEquals(4, body.get("version"));
        assertEquals(25, stock.quantity(PRODUCT));
    }

    @Tag("TC-419")
    @Test
    void theSameValueAnswers200WithTheUnchangedVersion() throws IOException {
        ResponseEntity<String> response = controller.set(PRODUCT.toString(), put("{\"quantity\":10}"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("\"3\"", response.getHeaders().getETag());
        assertEquals("2026-11-05T08:00:00.000Z", Json.parseObject(response.getBody()).get("updatedAt"));
        assertTrue(transaction.events.isEmpty());
    }

    @Tag("TC-419")
    @Test
    void theBoundariesOfTheQuantityAreAccepted() throws IOException {
        assertEquals(0, Json.parseObject(controller.set(PRODUCT.toString(), put("{\"quantity\":0}")).getBody()).get("quantity"));
        MockHttpServletRequest top = request(SELLER, "\"4\"", "application/json", "{\"quantity\":1000000}");
        assertEquals(1_000_000, Json.parseObject(controller.set(PRODUCT.toString(), top).getBody()).get("quantity"));
    }

    @Tag("TC-419")
    @Test
    void aStaleIfMatchIs412WithTheCurrentQuantityAndETagToRetryWith() {
        ProblemException e = refused(() -> controller.set(PRODUCT.toString(), request(SELLER, "\"2\"", "application/json", "{\"quantity\":25}")));
        assertEquals(ProblemType.VERSION_CONFLICT, e.type());
        assertEquals(10, e.extensions().get("available"));
        assertEquals("\"3\"", e.headers().get("ETag"));
    }

    @Tag("TC-419")
    @Test
    void theIfMatchHeaderIsRequiredAndMustBeAStrongETag() {
        assertEquals(ProblemType.PRECONDITION_REQUIRED,
                refused(() -> controller.set(PRODUCT.toString(), request(SELLER, null, "application/json", "{\"quantity\":25}"))).type());
        for (String bad : new String[] {"3", "W/\"3\"", "\"abc\"", "*", "\"3\", \"4\"", "\"1234567890\""}) {
            assertEquals(ProblemType.BAD_REQUEST,
                    refused(() -> controller.set(PRODUCT.toString(), request(SELLER, bad, "application/json", "{\"quantity\":25}"))).type(), bad);
        }
        assertEquals(10, stock.quantity(PRODUCT));
    }

    @Tag("TC-419")
    @Test
    void thePreconditionIsCheckedBeforeTheBody() {
        ProblemException noHeader = refused(() -> controller.set(PRODUCT.toString(), request(SELLER, null, "application/json", "{\"quantity\":-1}")));
        assertEquals(ProblemType.PRECONDITION_REQUIRED, noHeader.type(), "без If-Match тело не разбирается");
        ProblemException badBody = refused(() -> controller.set(PRODUCT.toString(), request(SELLER, "\"3\"", "application/json", "{\"quantity\":-1}")));
        assertEquals(ProblemType.VALIDATION_FAILED, badBody.type());
    }

    @Tag("TC-419")
    @Test
    void aVersionBeyondTheIntegerRangeIsABadRequestNotAnOverflow() {
        ProblemException e = refused(() -> controller.set(PRODUCT.toString(), request(SELLER, "\"3000000000\"", "application/json", "{\"quantity\":25}")));
        assertEquals(ProblemType.BAD_REQUEST, e.type());
    }

    @Tag("TC-419")
    @Test
    void quantityMustBeAWholeNumberFromZeroToOneMillion() {
        for (String bad : new String[] {"-1", "1000001", "1.5", "\"7\"", "null", "true", "[1]", "99999999999"}) {
            ProblemException e = refused(() -> controller.set(PRODUCT.toString(), put("{\"quantity\":" + bad + "}")));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), bad);
            assertEquals("/quantity", errors(e).get(0).get("pointer"), bad);
        }
        ProblemException range = refused(() -> controller.set(PRODUCT.toString(), put("{\"quantity\":-1}")));
        assertEquals("out_of_range", errors(range).get(0).get("code"));
        ProblemException format = refused(() -> controller.set(PRODUCT.toString(), put("{\"quantity\":1.5}")));
        assertEquals("invalid_format", errors(format).get(0).get("code"));
        assertEquals(10, stock.quantity(PRODUCT));
    }

    @Tag("TC-419")
    @Test
    void aMissingQuantityAndExtraFieldsAreReportedTogether() {
        ProblemException e = refused(() -> controller.set(PRODUCT.toString(), put("{\"qty\":5,\"version\":3}")));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(List.of("/qty", "/version", "/quantity"), errors(e).stream().map(m -> m.get("pointer")).toList());
        assertEquals(List.of("unknown_value", "unknown_value", "required"), errors(e).stream().map(m -> m.get("code")).toList());
        ProblemException empty = refused(() -> controller.set(PRODUCT.toString(), put("{}")));
        assertEquals(List.of("required"), errors(empty).stream().map(m -> m.get("code")).toList());
    }

    @Tag("TC-419")
    @Test
    void aBodyThatIsNotJsonOrNotAnObjectIs400() {
        for (String bad : new String[] {"", "{", "quantity=5", "[5]", "5"}) {
            assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.set(PRODUCT.toString(), put(bad))).type(), bad);
        }
    }

    @Tag("TC-419")
    @Test
    void onlyJsonIsAcceptedAndTheBodyIsSmall() {
        for (String type : new String[] {null, "text/plain", "text/csv", "application/x-www-form-urlencoded"}) {
            assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE,
                    refused(() -> controller.set(PRODUCT.toString(), request(SELLER, "\"3\"", type, "{\"quantity\":5}"))).type(), String.valueOf(type));
        }
        assertEquals(200, assertDoesNotThrow(() -> controller.set(PRODUCT.toString(), request(SELLER, "\"3\"", "Application/JSON; charset=utf-8", "{\"quantity\":5}")))
                .getStatusCode().value());
        String huge = "{\"quantity\":5,\"note\":\"" + "x".repeat(2000) + "\"}";
        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, refused(() -> controller.set(PRODUCT.toString(), request(SELLER, "\"4\"", "application/json", huge))).type());
    }

    @Tag("TC-419")
    @Test
    void theProductIdentifierIsCheckedFirst() {
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.set("x", put("{\"quantity\":5}"))).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.set(PRODUCT.toString().toUpperCase(), put("{\"quantity\":5}"))).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.set("x", request(SELLER, null, null, null))).type(), "раньше заголовков и тела");
    }

    @Tag("TC-419")
    @Test
    void theSellerComesFromTheIdentityNotFromTheRequest() {
        assertEquals(ProblemType.FORBIDDEN, refused(() -> controller.set(PRODUCT.toString(), request(null, "\"3\"", "application/json", "{\"quantity\":5}"))).type());
        UUID stranger = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b2");
        assertEquals(ProblemType.NOT_FOUND,
                refused(() -> controller.set(PRODUCT.toString(), request(stranger, "\"3\"", "application/json", "{\"quantity\":5}"))).type());
        assertEquals(10, stock.quantity(PRODUCT));
        assertFalse(transaction.events.stream().anyMatch(e -> e.type().equals("stock.changed")));
    }

    @Tag("TC-419")
    @Test
    void aKeyPoolProductIs409AndNoBodyDetailsLeakIntoTheError() {
        products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "key_pool", "published", 1));
        ProblemException e = refused(() -> controller.set(PRODUCT.toString(), put("{\"quantity\":5}")));
        assertEquals(ProblemType.ISSUANCE_METHOD_MISMATCH, e.type());
        assertFalse(e.getMessage().contains("5"), "в ошибке нет присланных значений");
    }

    private static <T> T assertDoesNotThrow(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }
}
