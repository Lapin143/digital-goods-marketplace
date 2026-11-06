package dgm.catalog.sellers.apikey.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.FakeTransaction;
import dgm.catalog.sellers.apikey.FakeApiKeys;
import dgm.catalog.sellers.apikey.domain.ApiKeyValues;
import dgm.catalog.sellers.apikey.service.ApiKeyService;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.kit.idempotency.IdempotencyFilter;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Операции API-ключей по схемам ApiKey, ApiKeyIssued, ApiKeyPage и ApiKeyCheck: разбор запроса, заголовки, ошибки, ответ выпуска со
 * значением и тело для повтора без него, проверка для шлюза.
 */
@Tag("TC-950")
@Tag("TC-951")
@Tag("TC-952")
@Tag("TC-953")
class ApiKeyControllerTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private static final String BASE = "/api/v1/seller/api-keys";
    private static final String CHECK = "/internal/v1/api-keys/check";
    private static final Set<String> KEY_FIELDS = Set.of("id", "name", "fingerprint", "status", "createdAt", "revokedAt");
    private static final Set<String> CHECK_FIELDS = Set.of("valid", "apiKeyId", "sellerId", "sellerStatus");

    private ManualClock clock;
    private FakeSellers sellers;
    private FakeApiKeys keys;
    private ApiKeyController controller;
    private ApiKeyCheckController checkController;
    private SellerProfile seller;
    private int counter;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        sellers = new FakeSellers();
        keys = new FakeApiKeys(sellers);
        seller = FakeSellers.profile(FakeSellers.USER, SellerStatus.APPROVED, FakeSellers.completeForm(), T0);
        sellers.put(seller);
        ApiKeyService service = new ApiKeyService(keys, sellers, new FakeTransaction(), clock, length -> {
            byte[] bytes = new byte[length];
            int n = ++counter;
            for (int i = 0; i < length; i++) {
                bytes[i] = (byte) (n * 37 + i * 11);
            }
            return bytes;
        });
        controller = new ApiKeyController(service);
        checkController = new ApiKeyCheckController(service);
    }

    /** Запрос продавца по токену: роль, область {@code seller.catalog}, второй фактор и профиль в claim {@code seller_id}. */
    private MockHttpServletRequest sellerRequest(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser(FakeSellers.USER.toString(), Set.of("seller.catalog"), Set.of("seller"),
                List.of("pwd", "otp"), true, seller.id().toString()));
        return request;
    }

    private MockHttpServletRequest json(String method, String uri, String body) {
        MockHttpServletRequest request = sellerRequest(method, uri);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private ResponseEntity<byte[]> create(String name) throws IOException {
        return controller.create(json("POST", BASE, Json.write(Map.of("name", name))));
    }

    private ResponseEntity<String> list(String... params) {
        MockHttpServletRequest request = sellerRequest("GET", BASE);
        for (int i = 0; i < params.length; i += 2) {
            request.setParameter(params[i], params[i + 1]);
        }
        return controller.list(request);
    }

    private ResponseEntity<String> revoke(String id) {
        return controller.revoke(id, sellerRequest("POST", BASE + "/" + id + "/revocation"));
    }

    private ResponseEntity<String> check(String body) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", CHECK);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return checkController.check(request);
    }

    /** Тело ответа как объект: ответ выпуска отдаётся байтами (значение ключа не должно попасть в журнал Spring), остальные строкой. */
    private static Map<String, Object> body(ResponseEntity<?> response) {
        Object body = response.getBody();
        return Json.parseObject(body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : (String) body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> apiKey(Map<String, Object> issued) {
        return (Map<String, Object>) issued.get("apiKey");
    }

    private static ProblemException problem(Executable action) {
        return assertThrows(ProblemException.class, action);
    }

    private static List<String> pairs(ProblemException e, String field) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        return errors.stream().map(m -> m.get(field) + ":" + m.get("code")).toList();
    }

    // ---- createApiKey ----

    @Test
    void createAnswers201WithTheValueShownOnceNoStoreAndTheLocation() throws Exception {
        ResponseEntity<byte[]> response = create("Склад");
        assertEquals(201, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(BASE, response.getHeaders().getLocation().toString());
        assertTrue(response.getHeaders().getContentType().toString().startsWith("application/json"));

        Map<String, Object> issued = body(response);
        assertEquals(Set.of("apiKey", "value", "shownOnce"), issued.keySet());
        assertEquals(true, issued.get("shownOnce"));
        assertTrue(ApiKeyValues.isWellFormed((String) issued.get("value")), String.valueOf(issued.get("value")));
        Map<String, Object> key = apiKey(issued);
        assertEquals(KEY_FIELDS, key.keySet());
        assertEquals("Склад", key.get("name"));
        assertEquals("active", key.get("status"));
        assertEquals("2026-11-04T10:00:00.000Z", key.get("createdAt"));
        assertNull(key.get("revokedAt"));
        assertEquals(ApiKeyValues.fingerprint((String) issued.get("value")), key.get("fingerprint"));
    }

    @Test
    void createLeavesTheIdempotencyGuardABodyWithoutTheValueForTheReplay() throws Exception {
        MockHttpServletRequest request = json("POST", BASE, "{\"name\":\"Склад\"}");
        ResponseEntity<byte[]> response = controller.create(request);
        String value = (String) body(response).get("value");

        Object replay = request.getAttribute(IdempotencyFilter.REPLAY_BODY_ATTRIBUTE);
        assertNotNull(replay);
        Map<String, Object> replayBody = Json.parseObject((String) replay);
        assertEquals(Set.of("apiKey", "shownOnce"), replayBody.keySet());
        assertEquals(true, replayBody.get("shownOnce"));
        assertEquals(apiKey(body(response)), apiKey(replayBody));
        assertFalse(((String) replay).contains(value), "значение ключа в теле для повтора");
        assertFalse(((String) replay).contains(value.substring(ApiKeyValues.PREFIX.length())), "значение без префикса в теле для повтора");
        assertFalse(((String) replay).contains(HexFormat.of().formatHex(ApiKeyValues.hash(value))), "хеш в теле для повтора");
    }

    @Test
    void createTrimsTheNameAndAllowsTheBoundsOfTheLength() throws Exception {
        assertEquals("Склад", apiKey(body(create("  Склад  "))).get("name"));
        assertEquals("а".repeat(60), apiKey(body(create("а".repeat(60)))).get("name"));
    }

    @Test
    void createRefusesWrongBodiesWithoutIssuingAnything() {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("{}", "/name:required");
        bodies.put("{\"name\":null}", "/name:required");
        bodies.put("{\"name\":\"\"}", "/name:required");
        bodies.put("{\"name\":\"   \"}", "/name:required");
        bodies.put("{\"name\":5}", "/name:invalid_format");
        bodies.put("{\"name\":[\"a\"]}", "/name:invalid_format");
        bodies.put("{\"name\":\"" + "а".repeat(61) + "\"}", "/name:too_long");
        bodies.put("{\"name\":\"a\\nb\"}", "/name:invalid_format");
        bodies.put("{\"name\":\"Склад\",\"value\":\"dgm_x\"}", "/value:unknown_value");
        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            ProblemException e = problem(() -> controller.create(json("POST", BASE, entry.getKey())));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), entry.getKey());
            assertEquals(List.of(entry.getValue()), pairs(e, "pointer"), entry.getKey());
        }
        assertEquals(0, keys.count());
    }

    @Test
    void createRefusesABodyThatIsNotAJsonObjectWith400() {
        for (String body : List.of("", "не json", "[]", "\"Склад\"", "{\"name\":")) {
            assertEquals(ProblemType.BAD_REQUEST, problem(() -> controller.create(json("POST", BASE, body))).type(), body);
        }
    }

    @Test
    void createRefusesAnotherContentTypeWith415() {
        MockHttpServletRequest request = sellerRequest("POST", BASE);
        request.setContentType("text/plain");
        request.setContent("{\"name\":\"Склад\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.create(request)).type());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.create(sellerRequest("POST", BASE))).type());
    }

    @Test
    void aBodyOverTheLimitIsRefusedWith413BeforeItIsRead() {
        String big = "{\"name\":\"" + "а".repeat(ApiKeyForms.MAX_BODY) + "\"}";
        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, problem(() -> controller.create(json("POST", BASE, big))).type());
        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, problem(() -> check("{\"keyHash\":\"" + "a".repeat(ApiKeyForms.MAX_BODY) + "\"}")).type());
        assertEquals(0, keys.count());
    }

    @Test
    void createRefusesATokenWithoutTheSellerProfileWith403() {
        MockHttpServletRequest request = json("POST", BASE, "{\"name\":\"Склад\"}");
        request.setAttribute(JwtFilter.USER_ATTRIBUTE,
                new AuthenticatedUser(FakeSellers.USER.toString(), Set.of("seller.catalog"), Set.of("seller"), List.of("pwd", "otp"), true));
        assertEquals(ProblemType.FORBIDDEN, problem(() -> controller.create(request)).type());
    }

    @Test
    void createWithoutAnyIdentityIs401() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", BASE);
        request.setContentType("application/json");
        request.setContent("{\"name\":\"Склад\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.UNAUTHENTICATED, problem(() -> controller.create(request)).type());
    }

    @Test
    void theSixthKeyAnswers409WithTheLimit() throws Exception {
        for (int i = 1; i <= 5; i++) {
            create("Ключ " + i);
        }
        ProblemException e = problem(() -> create("Шестой"));
        assertEquals(ProblemType.API_KEY_LIMIT, e.type());
        assertEquals(Map.of("limit", 5), e.extensions());
    }

    // ---- listApiKeys ----

    @Test
    void listAnswers200WithTheSchemaOfThePageAndNeverTheValue() throws Exception {
        String value = (String) body(create("Склад")).get("value");
        ResponseEntity<String> response = list();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> page = body(response);
        assertEquals(Set.of("items", "page"), page.keySet());
        Map<?, ?> info = (Map<?, ?>) page.get("page");
        assertEquals(Set.of("limit", "nextCursor"), info.keySet());
        assertEquals(20, info.get("limit"));
        assertNull(info.get("nextCursor"));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) page.get("items")).get(0);
        assertEquals(KEY_FIELDS, item.keySet());
        assertFalse(response.getBody().contains(value), "значение ключа в списке");
        assertFalse(response.getBody().contains(value.substring(ApiKeyValues.PREFIX.length())), "значение без префикса в списке");
    }

    @Test
    void listWalksAllKeysPageByPageWithTheCursorNewestFirst() throws Exception {
        List<Object> created = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            created.add(apiKey(body(create("Ключ " + i))).get("id"));
            clock.advance(Duration.ofSeconds(1));
        }
        List<Object> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Map<String, Object> page = cursor == null ? body(list("limit", "2")) : body(list("limit", "2", "cursor", cursor));
            for (Object item : (List<?>) page.get("items")) {
                seen.add(((Map<?, ?>) item).get("id"));
            }
            cursor = (String) ((Map<?, ?>) page.get("page")).get("nextCursor");
            pages++;
        } while (cursor != null);
        assertEquals(3, pages);
        assertEquals(created.reversed(), seen);
    }

    @Test
    void listFiltersByStatus() throws Exception {
        String first = (String) apiKey(body(create("Первый"))).get("id");
        create("Второй");
        revoke(first);
        List<?> revoked = (List<?>) body(list("status", "revoked")).get("items");
        List<?> active = (List<?>) body(list("status", "active")).get("items");
        assertEquals(List.of(first), revoked.stream().map(i -> ((Map<?, ?>) i).get("id")).toList());
        assertEquals(1, active.size());
        assertEquals("Второй", ((Map<?, ?>) active.get(0)).get("name"));
    }

    @Test
    void invalidListParametersAreCollectedIntoOne422() {
        ProblemException e = problem(() -> list("limit", "0", "cursor", "не-курсор", "color", "red", "status", "deleted"));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(Set.of("limit:out_of_range", "cursor:invalid_format", "color:unknown_value", "status:invalid_format"),
                Set.copyOf(pairs(e, "parameter")));
        assertEquals(ProblemType.VALIDATION_FAILED, problem(() -> list("limit", "101")).type());
    }

    // ---- revokeApiKey ----

    @Test
    void revokeAnswers200WithTheKeyInTheRevokedStatusAndNoStore() throws Exception {
        String id = (String) apiKey(body(create("Склад"))).get("id");
        clock.advance(Duration.ofMinutes(2));
        ResponseEntity<String> response = revoke(id);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> key = body(response);
        assertEquals(KEY_FIELDS, key.keySet());
        assertEquals(id, key.get("id"));
        assertEquals("revoked", key.get("status"));
        assertEquals("2026-11-04T10:02:00.000Z", key.get("revokedAt"));
        assertEquals(body(revoke(id)), key, "повтор отзыва даёт то же состояние");
    }

    @Test
    void revokeOfAnUnknownForeignOrMalformedIdentifierIs404() {
        String unknown = UUID.randomUUID().toString();
        assertEquals(ProblemType.NOT_FOUND, problem(() -> revoke(unknown)).type());
        for (String bad : List.of("1", "не-идентификатор", "0199E0A0-0000-7000-8000-000000000A01", unknown + "0", "0199e0a0000070008000000000000a01")) {
            assertEquals(ProblemType.NOT_FOUND, problem(() -> revoke(bad)).type(), bad);
        }
    }

    @Test
    void revokeAcceptsOnlyTheCanonicalLowercaseIdentifier() throws Exception {
        String id = (String) apiKey(body(create("Склад"))).get("id");
        assertEquals(ProblemType.NOT_FOUND, problem(() -> revoke(id.toUpperCase(Locale.ROOT))).type());
        Map<?, ?> item = (Map<?, ?>) ((List<?>) body(list()).get("items")).get(0);
        assertEquals("active", item.get("status"));
        assertEquals("revoked", body(revoke(id)).get("status"));
    }

    // ---- checkApiKey ----

    @Test
    void checkOfAnActiveKeyAnswersValidWithTheKeySellerAndStatus() throws Exception {
        Map<String, Object> issued = body(create("Склад"));
        String hash = HexFormat.of().formatHex(ApiKeyValues.hash((String) issued.get("value")));
        ResponseEntity<String> response = check("{\"keyHash\":\"" + hash + "\"}");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> answer = body(response);
        assertEquals(CHECK_FIELDS, answer.keySet());
        assertEquals(true, answer.get("valid"));
        assertEquals(apiKey(issued).get("id"), answer.get("apiKeyId"));
        assertEquals(seller.id().toString(), answer.get("sellerId"));
        assertEquals("approved", answer.get("sellerStatus"));
    }

    @Test
    void checkOfARevokedKeyAndOfAnUnknownKeyGiveTheSameAnswerWithoutAReason() throws Exception {
        Map<String, Object> issued = body(create("Склад"));
        revoke((String) apiKey(issued).get("id"));
        String revokedHash = HexFormat.of().formatHex(ApiKeyValues.hash((String) issued.get("value")));
        String unknownHash = HexFormat.of().formatHex(ApiKeyValues.hash("dgm_" + "A".repeat(40)));
        Map<String, Object> revoked = body(check("{\"keyHash\":\"" + revokedHash + "\"}"));
        Map<String, Object> unknown = body(check("{\"keyHash\":\"" + unknownHash + "\"}"));
        assertEquals(unknown, revoked);
        assertEquals(CHECK_FIELDS, revoked.keySet());
        assertEquals(false, revoked.get("valid"));
        assertNull(revoked.get("apiKeyId"));
        assertNull(revoked.get("sellerId"));
        assertNull(revoked.get("sellerStatus"));
    }

    @Test
    void checkOfAKeyOfABlockedSellerCarriesTheBlockedStatusForTheGateway() throws Exception {
        Map<String, Object> issued = body(create("Склад"));
        sellers.put(FakeSellers.profile(FakeSellers.USER, SellerStatus.BLOCKED, FakeSellers.completeForm(), T0));
        String hash = HexFormat.of().formatHex(ApiKeyValues.hash((String) issued.get("value")));
        Map<String, Object> answer = body(check("{\"keyHash\":\"" + hash + "\"}"));
        assertEquals(true, answer.get("valid"));
        assertEquals("blocked", answer.get("sellerStatus"));
    }

    @Test
    void checkRefusesBadBodies() {
        String hash = "a".repeat(64);
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("{}", "/keyHash:required");
        bodies.put("{\"keyHash\":null}", "/keyHash:required");
        bodies.put("{\"keyHash\":5}", "/keyHash:invalid_format");
        bodies.put("{\"keyHash\":\"" + "A".repeat(64) + "\"}", "/keyHash:invalid_format");
        bodies.put("{\"keyHash\":\"" + "a".repeat(63) + "\"}", "/keyHash:invalid_format");
        bodies.put("{\"keyHash\":\"" + "a".repeat(65) + "\"}", "/keyHash:invalid_format");
        bodies.put("{\"keyHash\":\"dgm_" + "A".repeat(40) + "\"}", "/keyHash:invalid_format");
        bodies.put("{\"keyHash\":\"" + hash + "\",\"value\":\"x\"}", "/value:unknown_value");
        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            ProblemException e = problem(() -> check(entry.getKey()));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), entry.getKey());
            assertEquals(List.of(entry.getValue()), pairs(e, "pointer"), entry.getKey());
        }
        assertEquals(ProblemType.BAD_REQUEST, problem(() -> check("не json")).type());
    }

    @Test
    void checkNeverEchoesTheHashBackInAnError() {
        String hash = "G".repeat(64);
        ProblemException e = problem(() -> check("{\"keyHash\":\"" + hash + "\"}"));
        assertFalse(e.getMessage().contains(hash));
        assertFalse(e.extensions().toString().contains(hash));
    }
}
