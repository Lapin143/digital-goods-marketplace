package dgm.catalog.sellers.integration.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.FakeTransaction;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.catalog.sellers.integration.FakeIntegrations;
import dgm.catalog.sellers.integration.crypto.IntegrationSecrets;
import dgm.catalog.sellers.integration.service.IntegrationService;
import dgm.kit.client.EndpointGuard;
import dgm.kit.crypto.SellerSecrets;
import dgm.kit.idempotency.IdempotencyFilter;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.secret.SecretValue;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.kit.time.ManualClock;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Операции подключения по схемам SellerIntegration, SellerIntegrationSaved, SecretRotation и SellerEndpoint: разбор запроса, заголовки
 * ({@code ETag}, {@code If-Match}, {@code Cache-Control}), ошибки, ответ с секретом один раз и тело для повтора без него, внутренний адрес
 * для сервисов.
 */
@Tag("TC-954")
@Tag("TC-959")
class IntegrationControllerTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private static final String BASE = "/api/v1/seller/integration";
    private static final String URL = "https://shop.seller.example/dgm";
    private static final SecretValue MASTER = SecretValue.of("master-for-catalog-tests-only-0123456789");
    private static final Set<String> INTEGRATION_FIELDS = Set.of("endpointUrl", "status", "secretVersion", "version", "updatedAt");

    private ManualClock clock;
    private FakeSellers sellers;
    private FakeIntegrations integrations;
    private SellerProfile seller;
    private IntegrationController controller;
    private SellerEndpointController endpoints;
    private final SellerSecrets derivation = new SellerSecrets(MASTER);

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        sellers = new FakeSellers();
        seller = FakeSellers.profile(FakeSellers.USER, SellerStatus.APPROVED, FakeSellers.completeForm(), T0);
        sellers.put(seller);
        integrations = new FakeIntegrations(sellers, clock);
        IntegrationService service = new IntegrationService(integrations, sellers, new FakeTransaction(),
                new EndpointGuard(IntegrationControllerTest::resolve, Set.of()), new IntegrationSecrets(derivation), clock);
        controller = new IntegrationController(service);
        endpoints = new SellerEndpointController(service);
    }

    private static InetAddress[] resolve(String host) throws UnknownHostException {
        if (host.equals("shop.seller.example") || host.equals("other.seller.example")) {
            return new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34})};
        }
        if (host.matches("[0-9.]+")) {
            return new InetAddress[] {InetAddress.getByName(host)};
        }
        throw new UnknownHostException(host);
    }

    /** Запрос продавца по токену: роль, область {@code seller.catalog}, второй фактор и профиль в claim {@code seller_id}. */
    private MockHttpServletRequest sellerRequest(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(JwtFilter.USER_ATTRIBUTE, new AuthenticatedUser(FakeSellers.USER.toString(), Set.of("seller.catalog"), Set.of("seller"),
                List.of("pwd", "otp"), true, seller.id().toString()));
        return request;
    }

    private MockHttpServletRequest put(String ifMatch, String body) {
        MockHttpServletRequest request = sellerRequest("PUT", BASE);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (ifMatch != null) {
            request.addHeader("If-Match", ifMatch);
        }
        return request;
    }

    private ResponseEntity<byte[]> save(String ifMatch, String body) throws Exception {
        return controller.set(put(ifMatch, body));
    }

    private static String bodyOf(String url) {
        return Json.write(Map.of("endpointUrl", url));
    }

    /** Тело ответа как объект: ответы с секретом (сохранение, смена) отдаются байтами, чтобы Spring не печатал их в журнал, остальные строкой. */
    private static Map<String, Object> body(ResponseEntity<?> response) {
        Object body = response.getBody();
        return Json.parseObject(body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : (String) body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> integration(Map<String, Object> saved) {
        return (Map<String, Object>) saved.get("integration");
    }

    private static ProblemException problem(Executable action) {
        return assertThrows(ProblemException.class, action);
    }

    private static List<String> pairs(ProblemException e, String field) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        return errors.stream().map(m -> m.get(field) + ":" + m.get("code")).toList();
    }

    // ---- getSellerIntegration ----

    @Test
    void readBeforeTheConnectionGivesAnEmptyAddressVersionZeroAndTheEtagOfZero() {
        ResponseEntity<String> response = controller.get(sellerRequest("GET", BASE));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("\"0\"", response.getHeaders().getETag());
        assertTrue(response.getHeaders().getContentType().toString().startsWith("application/json"));
        Map<String, Object> body = body(response);
        assertEquals(INTEGRATION_FIELDS, body.keySet());
        assertNull(body.get("endpointUrl"));
        assertEquals("disabled", body.get("status"));
        assertEquals(0, ((Number) body.get("secretVersion")).intValue());
        assertEquals(0, ((Number) body.get("version")).intValue());
        assertNull(body.get("updatedAt"));
    }

    @Test
    void readAfterTheConnectionGivesTheAddressTheVersionsAndNeverTheSecret() throws Exception {
        String secret = (String) body(save("\"0\"", bodyOf(URL))).get("secret");
        ResponseEntity<String> response = controller.get(sellerRequest("GET", BASE));
        assertEquals("\"1\"", response.getHeaders().getETag());
        Map<String, Object> body = body(response);
        assertEquals(INTEGRATION_FIELDS, body.keySet());
        assertEquals(URL, body.get("endpointUrl"));
        assertEquals("active", body.get("status"));
        assertEquals(1, ((Number) body.get("secretVersion")).intValue());
        assertEquals(1, ((Number) body.get("version")).intValue());
        assertEquals("2026-11-04T10:00:00.000Z", body.get("updatedAt"));
        assertFalse(response.getBody().contains(secret));
    }

    // ---- setSellerIntegration ----

    @Test
    void theFirstSaveAnswers200WithTheSecretAndTheEtagOfTheNewVersion() throws Exception {
        ResponseEntity<byte[]> response = save("\"0\"", bodyOf(URL));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("\"1\"", response.getHeaders().getETag());
        Map<String, Object> saved = body(response);
        assertEquals(Set.of("integration", "secret"), saved.keySet());
        assertEquals(INTEGRATION_FIELDS, integration(saved).keySet());
        assertEquals(URL, integration(saved).get("endpointUrl"));
        String secret = (String) saved.get("secret");
        assertTrue(secret.matches("[0-9a-f]{64}"), secret);
        assertEquals(derivation.secret(seller.id(), 1).reveal(), secret);
    }

    @Test
    void laterSavesHaveNoSecretFieldAndTheEtagGrows() throws Exception {
        save("\"0\"", bodyOf(URL));
        ResponseEntity<byte[]> response = save("\"1\"", Json.write(Map.of("endpointUrl", "https://other.seller.example/dgm", "status", "disabled")));
        assertEquals("\"2\"", response.getHeaders().getETag());
        Map<String, Object> saved = body(response);
        assertEquals(Set.of("integration"), saved.keySet());
        assertEquals("disabled", integration(saved).get("status"));
        assertEquals("https://other.seller.example/dgm", integration(saved).get("endpointUrl"));
    }

    @Test
    void aSaveWithoutIfMatchIs428AndAWrongOrWeakIfMatchIs400OrA412() throws Exception {
        assertEquals(ProblemType.PRECONDITION_REQUIRED, problem(() -> save(null, bodyOf(URL))).type());
        for (String bad : List.of("0", "W/\"0\"", "\"abc\"", "*", "\"-1\"", "\"\"")) {
            assertEquals(ProblemType.BAD_REQUEST, problem(() -> save(bad, bodyOf(URL))).type(), bad);
        }
        assertEquals(ProblemType.VERSION_CONFLICT, problem(() -> save("\"5\"", bodyOf(URL))).type());
        assertEquals(0, integrations.count());
    }

    @Test
    void theBodyIsReadStrictlyAndNothingIsSavedOnAnError() {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("{}", "/endpointUrl:required");
        bodies.put("{\"endpointUrl\":null}", "/endpointUrl:required");
        bodies.put("{\"endpointUrl\":\"\"}", "/endpointUrl:required");
        bodies.put("{\"endpointUrl\":\"   \"}", "/endpointUrl:required");
        bodies.put("{\"endpointUrl\":5}", "/endpointUrl:invalid_format");
        bodies.put("{\"endpointUrl\":\"" + URL + "\",\"status\":\"paused\"}", "/status:unknown_value");
        bodies.put("{\"endpointUrl\":\"" + URL + "\",\"status\":1}", "/status:invalid_format");
        bodies.put("{\"endpointUrl\":\"" + URL + "\",\"secret\":\"x\"}", "/secret:unknown_value");
        bodies.put("{\"endpointUrl\":\"https://shop.seller.example/" + "a".repeat(2048) + "\"}", "/endpointUrl:too_long");
        bodies.forEach((body, expected) -> {
            ProblemException e = problem(() -> save("\"0\"", body));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), body);
            assertTrue(pairs(e, "pointer").contains(expected), body + " -> " + pairs(e, "pointer"));
        });
        assertEquals(0, integrations.count());
    }

    @Test
    void aBodyThatIsNotJsonIs400AndAnotherContentTypeIs415() {
        assertEquals(ProblemType.BAD_REQUEST, problem(() -> save("\"0\"", "не json")).type());
        assertEquals(ProblemType.BAD_REQUEST, problem(() -> save("\"0\"", "[1]")).type());
        MockHttpServletRequest xml = put("\"0\"", "<a/>");
        xml.setContentType("application/xml");
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.set(xml)).type());
        MockHttpServletRequest none = put("\"0\"", bodyOf(URL));
        none.setContentType(null);
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.set(none)).type());
    }

    @Test
    void theAddressIsTrimmedAndAnAddressTheGuardRefusesAnswers422EndpointNotAllowed() throws Exception {
        assertEquals(URL, integration(body(save("\"0\"", bodyOf("  " + URL + "  ")))).get("endpointUrl"));
        for (String refused : List.of("http://shop.seller.example/dgm", "https://10.0.0.5/dgm", "https://shop.seller.example:8080/dgm")) {
            ProblemException e = problem(() -> save("\"1\"", bodyOf(refused)));
            assertEquals(ProblemType.ENDPOINT_NOT_ALLOWED, e.type(), refused);
            assertFalse(e.getMessage().contains("10.0.0.5"), "ответ не повторяет адрес");
        }
        assertEquals(URL, integrations.stored(seller.id()).endpointUrl());
    }

    // ---- rotateSellerIntegrationSecret ----

    @Test
    void rotationAnswers200WithTheNewSecretOnceAndLeavesTheIdempotencyGuardABodyWithoutIt() throws Exception {
        save("\"0\"", bodyOf(URL));
        MockHttpServletRequest request = sellerRequest("POST", BASE + "/secret-rotation");
        ResponseEntity<byte[]> response = controller.rotate(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = body(response);
        assertEquals(Set.of("secretVersion", "secret", "shownOnce"), body.keySet());
        assertEquals(2, ((Number) body.get("secretVersion")).intValue());
        assertEquals(true, body.get("shownOnce"));
        String secret = (String) body.get("secret");
        assertEquals(derivation.secret(seller.id(), 2).reveal(), secret);

        Object replay = request.getAttribute(IdempotencyFilter.REPLAY_BODY_ATTRIBUTE);
        assertNotNull(replay);
        Map<String, Object> replayBody = Json.parseObject((String) replay);
        assertEquals(Set.of("secretVersion", "shownOnce"), replayBody.keySet());
        assertEquals(2, ((Number) replayBody.get("secretVersion")).intValue());
        assertFalse(((String) replay).contains(secret), "секрет в теле для повтора");
        assertFalse(((String) replay).contains(derivation.secret(seller.id(), 1).reveal()), "прежний секрет в теле для повтора");
    }

    @Test
    void rotationWithoutAConnectionIs409AndLeavesNoReplayBody() {
        MockHttpServletRequest request = sellerRequest("POST", BASE + "/secret-rotation");
        assertEquals(ProblemType.STATE_CONFLICT, problem(() -> controller.rotate(request)).type());
        assertNull(request.getAttribute(IdempotencyFilter.REPLAY_BODY_ATTRIBUTE));
    }

    @Test
    void aSellerWhoIsNotApprovedGets403OnAllThreeCabinetOperations() {
        sellers.put(FakeSellers.profile(FakeSellers.USER, SellerStatus.BLOCKED, FakeSellers.completeForm(), T0));
        assertEquals(ProblemType.FORBIDDEN, problem(() -> controller.get(sellerRequest("GET", BASE))).type());
        assertEquals(ProblemType.FORBIDDEN, problem(() -> save("\"0\"", bodyOf(URL))).type());
        assertEquals(ProblemType.FORBIDDEN, problem(() -> controller.rotate(sellerRequest("POST", BASE + "/secret-rotation"))).type());
    }

    // ---- getSellerEndpoint ----

    @Test
    void theInternalAddressHasTheSellerTheAddressTheStatusAndTheSecretVersionButNoSecret() throws Exception {
        String secret = (String) body(save("\"0\"", bodyOf(URL))).get("secret");
        ResponseEntity<String> response = endpoints.endpoint(seller.id().toString());
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = body(response);
        assertEquals(Set.of("sellerId", "endpointUrl", "status", "secretVersion"), body.keySet());
        assertEquals(seller.id().toString(), body.get("sellerId"));
        assertEquals(URL, body.get("endpointUrl"));
        assertEquals("active", body.get("status"));
        assertEquals(1, ((Number) body.get("secretVersion")).intValue());
        assertFalse(response.getBody().contains(secret));
    }

    @Test
    void theInternalAddressIsTheSame404ForNoConnectionDisabledBlockedAndAMalformedId() throws Exception {
        List<String> messages = new java.util.ArrayList<>();
        messages.add(problem(() -> endpoints.endpoint(seller.id().toString())).getMessage());
        save("\"0\"", Json.write(Map.of("endpointUrl", URL, "status", "disabled")));
        messages.add(problem(() -> endpoints.endpoint(seller.id().toString())).getMessage());
        messages.add(problem(() -> endpoints.endpoint("not-an-id")).getMessage());
        messages.add(problem(() -> endpoints.endpoint(UUID.randomUUID().toString())).getMessage());
        messages.add(problem(() -> endpoints.endpoint(seller.id().toString().toUpperCase())).getMessage());
        for (String message : messages) {
            assertEquals(messages.get(0), message);
        }
        for (String id : List.of(seller.id().toString(), "not-an-id", UUID.randomUUID().toString())) {
            assertEquals(ProblemType.NOT_FOUND, problem(() -> endpoints.endpoint(id)).type(), id);
        }
        save("\"1\"", bodyOf(URL));
        sellers.put(FakeSellers.profile(FakeSellers.USER, SellerStatus.BLOCKED, FakeSellers.completeForm(), T0));
        assertEquals(ProblemType.NOT_FOUND, problem(() -> endpoints.endpoint(seller.id().toString())).type());
    }
}
