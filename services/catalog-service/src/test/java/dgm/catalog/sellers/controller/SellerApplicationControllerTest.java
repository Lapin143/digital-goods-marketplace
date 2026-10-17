package dgm.catalog.sellers.controller;

import static dgm.catalog.sellers.controller.ControllerFixture.body;
import static dgm.catalog.sellers.controller.ControllerFixture.json;
import static dgm.catalog.sellers.controller.ControllerFixture.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.domain.DocumentType;
import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Операции заявки заявителя по схемам SellerApplicationForm и SellerApplication: разбор тела, заголовки, ETag и ошибки. */
class SellerApplicationControllerTest {

    private static final String FORM = "{\"sellerType\":\"self_employed\",\"name\":\"Студия «Лунный свет»\",\"payoutDetails\":{\"recipientName\":"
            + "\"Иванов Иван Иванович\",\"bankName\":\"Банк «Пример»\",\"bik\":\"044525000\",\"accountNumber\":\"40817810000000000001\"},"
            + "\"assortmentDescription\":\"Ключи к инди-играм для Steam\"}";
    private static final Set<String> APPLICATION_FIELDS = Set.of("sellerId", "status", "sellerType", "name", "payoutDetails", "assortmentDescription",
            "documents", "decisionReason", "rejectedAt", "nextApplicationAt", "submittedAt", "reviewDeadlineAt", "createdAt", "updatedAt");
    private static final Set<String> ALWAYS_PRESENT = Set.of("sellerId", "status", "documents", "decisionReason", "rejectedAt", "nextApplicationAt",
            "submittedAt", "reviewDeadlineAt", "createdAt", "updatedAt");

    private ControllerFixture fx;
    private SellerApplicationController controller;

    @BeforeEach
    void setUp() {
        fx = new ControllerFixture();
        controller = fx.applicationController;
    }

    private ResponseEntity<String> create(String body) throws IOException {
        return controller.create(json("POST", "/api/v1/seller-applications", body));
    }

    private ResponseEntity<String> replace(String ifMatch, String body) throws IOException {
        MockHttpServletRequest request = json("PUT", "/api/v1/seller-applications/current", body);
        if (ifMatch != null) {
            request.addHeader("If-Match", ifMatch);
        }
        return controller.replace(request);
    }

    private static ProblemException problem(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(ProblemException.class, action);
    }

    private static List<Map<String, Object>> errors(ProblemException e) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        return errors;
    }

    private static String pointer(ProblemException e, int index) {
        return (String) errors(e).get(index).get("pointer");
    }

    private static String code(ProblemException e, int index) {
        return (String) errors(e).get(index).get("code");
    }

    private void addDocument(SellerProfile profile) {
        UUID id = UUID.fromString("0199e0a0-0000-7000-8000-000000000901");
        fx.sellers.putDocument(new SellerDocument(id, profile.id(), SellerDocument.objectKey(profile.id(), id), "certificate.pdf", DocumentType.PDF,
                482_113, ControllerFixture.T0));
    }

    // ---------------------------------------------------------------- createSellerApplication

    @Test
    void createAnswers201WithETagLocationAndTheFullApplication() throws IOException {
        ResponseEntity<String> response = create(FORM);
        assertEquals(201, response.getStatusCode().value());
        assertEquals("\"1\"", response.getHeaders().getETag());
        assertEquals("/api/v1/seller-applications/current", response.getHeaders().getFirst("Location"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(response.getHeaders().getContentType().toString().startsWith("application/json"));

        Map<String, Object> body = body(response);
        assertEquals(APPLICATION_FIELDS, body.keySet());
        assertEquals("draft", body.get("status"));
        assertEquals("self_employed", body.get("sellerType"));
        assertEquals("Студия «Лунный свет»", body.get("name"));
        assertEquals(Map.of("recipientName", "Иванов Иван Иванович", "bankName", "Банк «Пример»", "bik", "044525000",
                "accountNumber", "40817810000000000001"), body.get("payoutDetails"));
        assertEquals("Ключи к инди-играм для Steam", body.get("assortmentDescription"));
        assertEquals(List.of(), body.get("documents"));
        assertEquals("2026-10-03T12:00:00.000Z", body.get("createdAt"));
        assertEquals("2026-10-03T12:00:00.000Z", body.get("updatedAt"));
        assertEquals(fx.sellers.stored(FakeSellers.USER).id().toString(), body.get("sellerId"));
        for (String nullable : List.of("decisionReason", "rejectedAt", "nextApplicationAt", "submittedAt", "reviewDeadlineAt")) {
            assertTrue(body.containsKey(nullable) && body.get(nullable) == null, nullable + " присутствует и равно null");
        }
    }

    @Test
    void emptyObjectStartsAnEmptyDraftWithoutOptionalFields() throws IOException {
        Map<String, Object> body = body(create("{}"));
        assertEquals(ALWAYS_PRESENT, body.keySet());
        assertEquals("draft", body.get("status"));
    }

    @Test
    void partialFormIsSavedAsItIs() throws IOException {
        Map<String, Object> body = body(create("{\"name\":\"Студия\",\"payoutDetails\":{\"bankName\":\"Банк\"}}"));
        assertEquals("Студия", body.get("name"));
        assertEquals(Map.of("bankName", "Банк"), body.get("payoutDetails"));
        assertEquals(false, body.containsKey("sellerType"));
    }

    @Test
    void secondCreateIs409WithTheCurrentStatus() throws IOException {
        create(FORM);
        ProblemException e = problem(() -> create(FORM));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("draft", e.extensions().get("currentStatus"));
    }

    @Test
    void createRequiresAJsonContentType() {
        MockHttpServletRequest request = request("POST", "/api/v1/seller-applications");
        request.setContentType("text/plain");
        request.setContent("{}".getBytes());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.create(request)).type());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.create(request("POST", "/api/v1/seller-applications"))).type());
        assertEquals(0, fx.sellers.profileCount());
    }

    @Test
    void bodyThatIsNotAJsonObjectIs400() {
        for (String body : new String[] {"", "не json", "[]", "\"text\"", "{\"name\":"}) {
            assertEquals(ProblemType.BAD_REQUEST, problem(() -> create(body)).type(), body);
        }
    }

    @Test
    void unknownFieldsAreListedWithTheirPointers() {
        ProblemException e = problem(() -> create("{\"foo\":1,\"payoutDetails\":{\"swift\":\"X\"}}"));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(Set.of("/foo", "/payoutDetails/swift"), Set.of(pointer(e, 0), pointer(e, 1)));
        assertEquals("unknown_value", code(e, 0));
    }

    @Test
    void fieldsOfTheWrongTypeAreInvalidFormat() {
        ProblemException e = problem(() -> create("{\"name\":123,\"sellerType\":true,\"payoutDetails\":\"банк\",\"assortmentDescription\":[]}"));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(Set.of("/name", "/sellerType", "/payoutDetails", "/assortmentDescription"),
                errors(e).stream().map(m -> (String) m.get("pointer")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(errors(e).stream().allMatch(m -> "invalid_format".equals(m.get("code"))));
        assertEquals(0, fx.sellers.profileCount());
    }

    @Test
    void unknownSellerTypeNamesTheAllowedOnes() {
        ProblemException e = problem(() -> create("{\"sellerType\":\"company\"}"));
        assertEquals("/sellerType", pointer(e, 0));
        assertEquals("unknown_value", code(e, 0));
        assertTrue(((String) errors(e).get(0).get("detail")).contains("sole_proprietor"));
    }

    @Test
    void formatRulesOfTheDomainAreAppliedWithPointersInsidePayoutDetails() {
        ProblemException e = problem(() -> create("{\"name\":\"\",\"payoutDetails\":{\"bik\":\"123\",\"accountNumber\":\"abc\"}}"));
        assertEquals(List.of("/name", "/payoutDetails/bik", "/payoutDetails/accountNumber"), errors(e).stream().map(m -> (String) m.get("pointer")).toList());
        assertEquals(List.of("too_short", "invalid_format", "invalid_format"), errors(e).stream().map(m -> (String) m.get("code")).toList());
        assertTrue(errors(e).stream().allMatch(m -> m.get("detail") instanceof String));
    }

    @Test
    void nullEqualsAnAbsentFieldAndSpacesAroundTextAreCut() throws IOException {
        Map<String, Object> body = body(create("{\"name\":\"  Студия  \",\"sellerType\":null,\"payoutDetails\":null,\"assortmentDescription\":null}"));
        assertEquals("Студия", body.get("name"));
        assertEquals(false, body.containsKey("sellerType"));
        assertEquals(false, body.containsKey("payoutDetails"));
    }

    // ---------------------------------------------------------------- getCurrentSellerApplication

    @Test
    void currentIs404BeforeTheFirstApplication() {
        assertEquals(ProblemType.NOT_FOUND, problem(() -> controller.current(request("GET", "/api/v1/seller-applications/current"))).type());
    }

    @Test
    void currentShowsTheApplicationWithETagAndDocuments() throws IOException {
        create(FORM);
        addDocument(fx.sellers.stored(FakeSellers.USER));
        ResponseEntity<String> response = controller.current(request("GET", "/api/v1/seller-applications/current"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("\"1\"", response.getHeaders().getETag());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        List<?> documents = (List<?>) body(response).get("documents");
        assertEquals(1, documents.size());
        Map<?, ?> document = (Map<?, ?>) documents.get(0);
        assertEquals(Set.of("documentId", "fileName", "contentType", "sizeBytes", "uploadedAt"), document.keySet());
        assertEquals("certificate.pdf", document.get("fileName"));
        assertEquals("application/pdf", document.get("contentType"));
        assertEquals(482_113, ((Number) document.get("sizeBytes")).intValue());
        assertEquals("2026-10-03T12:00:00.000Z", document.get("uploadedAt"));
    }

    @Test
    void uploadingADocumentDoesNotChangeTheETagOfTheForm() throws IOException {
        create(FORM);
        String before = controller.current(request("GET", "/api/v1/seller-applications/current")).getHeaders().getETag();
        addDocument(fx.sellers.stored(FakeSellers.USER));
        String after = controller.current(request("GET", "/api/v1/seller-applications/current")).getHeaders().getETag();
        assertEquals(before, after);
    }

    // ---------------------------------------------------------------- replaceSellerApplicationDraft

    @Test
    void replaceWithoutIfMatchIs428() throws IOException {
        create(FORM);
        assertEquals(ProblemType.PRECONDITION_REQUIRED, problem(() -> replace(null, FORM)).type());
        assertEquals(ProblemType.PRECONDITION_REQUIRED, problem(() -> replace("  ", FORM)).type());
    }

    @Test
    void ifMatchMustBeAStrongETagOfTheVersion() throws IOException {
        create(FORM);
        for (String bad : new String[] {"1", "W/\"1\"", "\"x\"", "\"\"", "\"1\", \"2\"", "*", "\"1234567890\""}) {
            assertEquals(ProblemType.BAD_REQUEST, problem(() -> replace(bad, FORM)).type(), bad);
        }
    }

    @Test
    void replaceWithTheRightVersionAnswers200WithTheNextETag() throws IOException {
        create("{}");
        ResponseEntity<String> response = replace("\"1\"", FORM);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("\"2\"", response.getHeaders().getETag());
        assertEquals("Студия «Лунный свет»", body(response).get("name"));
        assertEquals("draft", body(response).get("status"));
    }

    @Test
    void replaceWithAStaleVersionIs412() throws IOException {
        create("{}");
        replace("\"1\"", FORM);
        assertEquals(ProblemType.VERSION_CONFLICT, problem(() -> replace("\"1\"", "{}")).type());
    }

    @Test
    void replaceWithoutAnApplicationIs404() {
        assertEquals(ProblemType.NOT_FOUND, problem(() -> replace("\"1\"", FORM)).type());
    }

    @Test
    void replaceOfAnApplicationOnReviewIs409() throws IOException {
        fx.sellers.put(FakeSellers.profile(FakeSellers.USER, SellerStatus.ON_REVIEW, FakeSellers.completeForm(), ControllerFixture.T0));
        ProblemException e = problem(() -> replace("\"1\"", FORM));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("on_review", e.extensions().get("currentStatus"));
    }

    @Test
    void replaceOfAReturnedApplicationNeedsTheCompleteForm() throws IOException {
        fx.sellers.put(FakeSellers.profile(FakeSellers.USER, SellerStatus.RETURNED, FakeSellers.completeForm(), ControllerFixture.T0));
        ProblemException e = problem(() -> replace("\"1\"", "{\"name\":\"Студия\"}"));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals("required", code(e, 0));
        assertEquals("/sellerType", pointer(e, 0));
        assertEquals(1, fx.sellers.stored(FakeSellers.USER).version());
    }

    @Test
    void replaceOfARejectedApplicationReopensItAndShowsTheRejectionAndTheNextDate() throws IOException {
        fx.sellers.put(FakeSellers.profile(FakeSellers.USER, SellerStatus.REJECTED, FakeSellers.completeForm(), ControllerFixture.T0));
        Map<String, Object> body = body(replace("\"1\"", FORM));
        assertEquals("draft", body.get("status"));
        assertEquals("Причина решения", body.get("decisionReason"));
        assertEquals("2026-10-03T12:00:00.000Z", body.get("rejectedAt"));
        assertEquals("2026-10-04T12:00:00.000Z", body.get("nextApplicationAt"));
    }

    @Test
    void replaceClearsFieldsThatAreMissingInTheNewBody() throws IOException {
        create(FORM);
        Map<String, Object> body = body(replace("\"1\"", "{\"name\":\"Студия\"}"));
        assertEquals(false, body.containsKey("sellerType"));
        assertEquals(false, body.containsKey("payoutDetails"));
        assertEquals(false, body.containsKey("assortmentDescription"));
    }

    @Test
    void replaceChecksThePreconditionBeforeTheBody() throws IOException {
        create("{}");
        MockHttpServletRequest request = request("PUT", "/api/v1/seller-applications/current");
        request.setContentType("text/plain");
        assertEquals(ProblemType.PRECONDITION_REQUIRED, problem(() -> controller.replace(request)).type());
    }

    // ---------------------------------------------------------------- submitSellerApplication

    @Test
    void submitPutsTheApplicationOnReviewWithAThreeDayDeadline() throws IOException {
        create(FORM);
        addDocument(fx.sellers.stored(FakeSellers.USER));
        fx.clock.advance(Duration.ofHours(1));
        ResponseEntity<String> response = controller.submit(request("POST", "/api/v1/seller-applications/current/submit"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("\"2\"", response.getHeaders().getETag());
        Map<String, Object> body = body(response);
        assertEquals("on_review", body.get("status"));
        assertEquals("2026-10-03T13:00:00.000Z", body.get("submittedAt"));
        assertEquals("2026-10-06T13:00:00.000Z", body.get("reviewDeadlineAt"));
        assertNull(body.get("decisionReason"));
        assertEquals(1, ((List<?>) body.get("documents")).size());
    }

    @Test
    void submitWithoutADocumentIs422WithThePointerOfTheMissingPart() throws IOException {
        create(FORM);
        ProblemException e = problem(() -> controller.submit(request("POST", "/api/v1/seller-applications/current/submit")));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals("/documents", pointer(e, 0));
        assertEquals("required", code(e, 0));
    }

    @Test
    void submitWithAnIncompleteFormListsEveryMissingField() throws IOException {
        create("{\"name\":\"Студия\"}");
        addDocument(fx.sellers.stored(FakeSellers.USER));
        ProblemException e = problem(() -> controller.submit(request("POST", "/api/v1/seller-applications/current/submit")));
        assertEquals(List.of("/sellerType", "/payoutDetails/recipientName", "/payoutDetails/bankName", "/payoutDetails/bik",
                "/payoutDetails/accountNumber", "/assortmentDescription"), errors(e).stream().map(m -> (String) m.get("pointer")).toList());
    }

    @Test
    void submitDuringThePauseIs409WithTheDateAndRetryAfter() throws IOException {
        SellerProfile draft = new SellerProfile(UUID.fromString("0199e0a0-0000-7000-8000-000000000101"), FakeSellers.USER, SellerStatus.DRAFT,
                FakeSellers.completeForm(), "Причина", ControllerFixture.T0, ControllerFixture.T0, null, null, 2, ControllerFixture.T0,
                ControllerFixture.T0);
        fx.sellers.put(draft);
        addDocument(draft);
        fx.clock.advance(Duration.ofHours(23));
        ProblemException e = problem(() -> controller.submit(request("POST", "/api/v1/seller-applications/current/submit")));
        assertEquals(ProblemType.APPLICATION_PAUSE_ACTIVE, e.type());
        assertEquals("2026-10-04T12:00:00.000Z", e.extensions().get("retryAfterAt"));
        assertEquals("3600", e.headers().get("Retry-After"));
    }

    @Test
    void submitWithoutAnApplicationIs404AndSecondSubmitIs409() throws IOException {
        assertEquals(ProblemType.NOT_FOUND, problem(() -> controller.submit(request("POST", "/api/v1/seller-applications/current/submit"))).type());
        create(FORM);
        addDocument(fx.sellers.stored(FakeSellers.USER));
        controller.submit(request("POST", "/api/v1/seller-applications/current/submit"));
        ProblemException e = problem(() -> controller.submit(request("POST", "/api/v1/seller-applications/current/submit")));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("on_review", e.extensions().get("currentStatus"));
    }

    // ---------------------------------------------------------------- пользователь

    @Test
    void requestWithoutAUserIs401() {
        MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/api/v1/seller-applications/current");
        assertEquals(ProblemType.UNAUTHENTICATED, problem(() -> controller.current(anonymous)).type());
    }

    @Test
    void eachUserSeesOnlyHisOwnApplication() throws IOException {
        create(FORM);
        MockHttpServletRequest other = request("GET", "/api/v1/seller-applications/current", FakeSellers.OTHER_USER);
        assertEquals(ProblemType.NOT_FOUND, problem(() -> controller.current(other)).type());
    }

    @Test
    void completeFormHelperMatchesTheRequestBody() throws IOException {
        create(FORM);
        SellerForm stored = fx.sellers.stored(FakeSellers.USER).form();
        assertEquals(FakeSellers.completeForm(), stored);
    }
}
