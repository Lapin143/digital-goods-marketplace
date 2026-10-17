package dgm.inventory.inventory.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.crypto.KeyCipher;
import dgm.inventory.inventory.crypto.KeyHasher;
import dgm.inventory.inventory.keys.controller.KeyBodies;
import dgm.inventory.inventory.keys.controller.KeyPoolController;
import dgm.inventory.inventory.keys.service.DataKeys;
import dgm.inventory.inventory.keys.service.KeyPoolService;
import dgm.inventory.inventory.registry.ProductCopy;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Три операции пула по схемам KeyBatchRequest, KeyUploadReport и StockInfo: разбор тела, заголовки, ошибки (US-4.2, US-4.3, US-4.5). */
class KeyPoolControllerTest {

    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final String BASE = "/api/v1/seller/products/" + PRODUCT;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00.123Z"), ZoneOffset.UTC);

    private FakePool pool;
    private KeyPoolController controller;

    @BeforeEach
    void setUp() {
        SecureRandom random = new SecureRandom();
        KeyCipher cipher = new KeyCipher(new byte[32], random);
        pool = new FakePool();
        pool.products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "key_pool", "draft", 1));
        KeyHasher hasher = new KeyHasher("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        controller = new KeyPoolController(new KeyPoolService(pool.products, pool.keys, new DataKeys(pool.dataKeys, cipher), cipher, hasher,
                pool.transaction, CLOCK));
    }

    private static MockHttpServletRequest request(String method, String uri, UUID seller, String contentType, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser("0199e0a0-0000-4000-8000-0000000000a1", Set.of("seller.catalog"),
                Set.of("seller"), List.of("pwd", "otp"), true, seller == null ? null : seller.toString()));
        if (contentType != null) {
            request.setContentType(contentType);
        }
        if (body != null) {
            request.setContent(body);
        }
        return request;
    }

    private static MockHttpServletRequest json(String body) {
        return request("POST", BASE + "/key-batches", SELLER, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest csv(String body) {
        return request("POST", BASE + "/key-files", SELLER, "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    private interface ThrowingCall {
        void run() throws IOException;
    }

    private static ProblemException refused(ThrowingCall call) {
        return assertThrows(ProblemException.class, call::run);
    }

    @SuppressWarnings("unchecked")
    private static List<String> pointers(ProblemException e) {
        return ((List<Map<String, Object>>) e.extensions().get("errors")).stream().map(m -> (String) m.get("pointer")).toList();
    }

    @Tag("TC-401")
    @Test
    void theBatchAnswers200WithTheReportOfTheSchemaAndNoStoreAndNeverTheValues() throws IOException {
        ResponseEntity<String> response = controller.uploadBatch(PRODUCT.toString(), json("{\"keys\":[\"XXXXX-00001\",\"XXXXX-00002\",\"XXXXX-00002\",\"\",\"XXXXX-00004\"]}"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = Json.parseObject(response.getBody());
        assertEquals(Set.of("accepted", "rejectedTotal", "rejected", "available", "poolLimit"), body.keySet());
        assertEquals(3, body.get("accepted"));
        assertEquals(2, body.get("rejectedTotal"));
        assertEquals(List.of(Map.of("line", 3, "reason", "duplicate_in_upload"), Map.of("line", 4, "reason", "empty")), body.get("rejected"));
        assertEquals(3, body.get("available"));
        assertEquals(100000, body.get("poolLimit"));
        assertFalse(response.getBody().contains("XXXXX"), "значений ключей в ответе нет");
    }

    @Tag("TC-402")
    @Test
    void theFileAnswersWithTheSameReportAndTakesCrLfAndTheLastLineWithoutANewline() throws IOException {
        ResponseEntity<String> response = controller.uploadFile(PRODUCT.toString(), csv("A-1\r\nA-2\r\n\r\nA-1\r\nA-3"));
        Map<String, Object> body = Json.parseObject(response.getBody());
        assertEquals(3, body.get("accepted"));
        assertEquals(List.of(Map.of("line", 3, "reason", "empty"), Map.of("line", 4, "reason", "duplicate_in_upload")), body.get("rejected"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Tag("TC-402")
    @Test
    void theTrailingNewlineDoesNotMakeAnEmptyLineAndTheByteOrderMarkIsDropped() throws IOException {
        MockHttpServletRequest request = csv("\uFEFFA-1\nA-2\n");
        assertEquals(List.of("A-1", "A-2"), KeyBodies.csv(request));
    }

    @Tag("TC-403")
    @Test
    void batchBodyViolationsAreValidationErrorsWithPointers() {
        assertEquals(List.of("/keys"), pointers(refused(() -> KeyBodies.batch(json("{}")))));
        assertEquals(List.of("/keys"), pointers(refused(() -> KeyBodies.batch(json("{\"keys\":\"A-1\"}")))));
        assertEquals(List.of("/keys"), pointers(refused(() -> KeyBodies.batch(json("{\"keys\":[]}")))));
        assertEquals(List.of("/keys/1"), pointers(refused(() -> KeyBodies.batch(json("{\"keys\":[\"A\",42]}")))));
        assertEquals(List.of("/other"), pointers(refused(() -> KeyBodies.batch(json("{\"keys\":[\"A\"],\"other\":1}")))));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 10_001; i++) {
            many.add("\"K" + i + "\"");
        }
        assertEquals(List.of("/keys"), pointers(refused(() -> KeyBodies.batch(json("{\"keys\":[" + String.join(",", many) + "]}")))));
        assertEquals(0, pool.keys.stored(PRODUCT));
    }

    @Tag("TC-403")
    @Test
    void exactlyTenThousandKeysAreAccepted() throws IOException {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            many.add("\"K" + i + "\"");
        }
        assertEquals(10_000, KeyBodies.batch(json("{\"keys\":[" + String.join(",", many) + "]}")).size());
    }

    @Tag("TC-403")
    @Test
    void theMediaTypeIsCheckedPerOperation() {
        ProblemException wrongForBatch = refused(() -> KeyBodies.batch(csv("A-1")));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, wrongForBatch.type());
        ProblemException wrongForFile = refused(() -> KeyBodies.csv(json("{\"keys\":[\"A\"]}")));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, wrongForFile.type());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, refused(() -> KeyBodies.csv(request("POST", BASE, SELLER, null, new byte[0]))).type());
    }

    @Tag("TC-403")
    @Test
    void aBodyThatIsNotAJsonObjectIs400() {
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> KeyBodies.batch(json("это не JSON"))).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> KeyBodies.batch(json("[\"A\"]"))).type());
    }

    @Tag("TC-403")
    @Test
    void anEmptyFileAndAFileInAnotherEncodingAreValidationErrors() {
        assertEquals(List.of("/file"), pointers(refused(() -> KeyBodies.csv(csv("")))));
        MockHttpServletRequest latin1 = request("POST", BASE, SELLER, "text/csv", new byte[] {'A', (byte) 0xE9, '\n'});
        assertEquals(List.of("/file"), pointers(refused(() -> KeyBodies.csv(latin1))));
    }

    @Tag("TC-403")
    @Test
    void moreThanTenThousandLinesIs413AndExactlyTenThousandIsFine() throws IOException {
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            ok.append("K").append(i).append('\n');
        }
        assertEquals(10_000, KeyBodies.csv(csv(ok.toString())).size());
        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, refused(() -> KeyBodies.csv(csv(ok + "K-extra\n"))).type());
    }

    @Tag("TC-403")
    @Test
    void aBodyOverTwoMegabytesIs413() {
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, refused(() -> KeyBodies.csv(request("POST", BASE, SELLER, "text/csv", big))).type());
    }

    @Tag("TC-403")
    @Test
    void aBadProductIdentifierIs400BeforeTheBodyIsRead() {
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.uploadBatch("x", json("{\"keys\":[\"A\"]}"))).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.uploadFile(PRODUCT.toString().toUpperCase(), csv("A"))).type());
        assertEquals(ProblemType.BAD_REQUEST, refused(() -> controller.stock("x", request("GET", BASE + "/stock", SELLER, null, null))).type());
    }

    @Tag("TC-408")
    @Test
    void aTokenWithoutTheSellerIdClaimIsNotAnOwner() {
        MockHttpServletRequest noSeller = request("POST", BASE + "/key-batches", null, "application/json", "{\"keys\":[\"A\"]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.FORBIDDEN, refused(() -> controller.uploadBatch(PRODUCT.toString(), noSeller)).type());
    }

    @Tag("TC-408")
    @Test
    void anotherSellersProductIsNotFound() {
        UUID stranger = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b2");
        MockHttpServletRequest request = request("POST", BASE + "/key-batches", stranger, "application/json", "{\"keys\":[\"A\"]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.NOT_FOUND, refused(() -> controller.uploadBatch(PRODUCT.toString(), request)).type());
        assertEquals(0, pool.keys.stored(PRODUCT));
    }

    @Tag("TC-406")
    @Test
    void stockMatchesTheSchemaAndIsNotCached() throws IOException {
        controller.uploadBatch(PRODUCT.toString(), json("{\"keys\":[\"A-1\",\"A-2\"]}"));
        ResponseEntity<String> response = controller.stock(PRODUCT.toString(), request("GET", BASE + "/stock", SELLER, null, null));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = Json.parseObject(response.getBody());
        assertEquals(Set.of("productId", "available", "reserved", "issued", "poolSize", "poolLimit", "updatedAt"), body.keySet());
        assertEquals(PRODUCT.toString(), body.get("productId"));
        assertEquals(2, body.get("available"));
        assertEquals(0, body.get("reserved"));
        assertEquals(0, body.get("issued"));
        assertEquals(2, body.get("poolSize"));
        assertEquals("2026-10-17T12:00:00.123Z", body.get("updatedAt"));
        assertNull(response.getHeaders().getETag());
        assertTrue(response.getHeaders().getContentType().toString().startsWith("application/json"));
    }
}
