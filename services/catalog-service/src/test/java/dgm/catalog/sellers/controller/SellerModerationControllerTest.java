package dgm.catalog.sellers.controller;

import static dgm.catalog.sellers.controller.ControllerFixture.MODERATOR;
import static dgm.catalog.sellers.controller.ControllerFixture.T0;
import static dgm.catalog.sellers.controller.ControllerFixture.body;
import static dgm.catalog.sellers.controller.ControllerFixture.moderator;
import static dgm.catalog.sellers.controller.ControllerFixture.moderatorJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.domain.DocumentType;
import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.web.PageCursor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Операции модератора по схемам SellerApplicationQueuePage, SellerApplicationCard и SellerApplication: разбор запроса, заголовки и ошибки.
 */
@Tag("TC-208")
@Tag("TC-210")
@Tag("TC-211")
@Tag("TC-213")
@Tag("TC-214")
@Tag("TC-215")
class SellerModerationControllerTest {

    private static final String BASE = "/api/v1/staff/seller-applications";
    private static final Duration REVIEW = Duration.ofDays(3);
    private static final Set<String> QUEUE_ITEM_FIELDS = Set.of("sellerId", "name", "sellerType", "documentsCount", "submittedAt", "reviewDeadlineAt",
            "overdue");
    private static final Set<String> CARD_FIELDS = Set.of("sellerId", "userId", "status", "sellerType", "name", "payoutDetails", "assortmentDescription",
            "documents", "submittedAt", "reviewDeadlineAt");
    private static final Set<String> APPLICATION_FIELDS = Set.of("sellerId", "status", "sellerType", "name", "payoutDetails", "assortmentDescription",
            "documents", "decisionReason", "rejectedAt", "nextApplicationAt", "submittedAt", "reviewDeadlineAt", "createdAt", "updatedAt");

    private ControllerFixture fx;
    private SellerModerationController controller;

    @BeforeEach
    void setUp() {
        fx = new ControllerFixture();
        controller = fx.moderationController;
    }

    private static UUID user(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-4000-8000-%012d", n));
    }

    /** Идентификатор профиля тот же, что даёт {@link FakeSellers#profile}: по пользователю. */
    private static UUID sellerOf(int n) {
        return UUID.nameUUIDFromBytes(("profile-" + user(n)).getBytes(StandardCharsets.UTF_8));
    }

    /** Заявка на проверке: подана в {@code submittedAt}, срок рассмотрения три дня. */
    private SellerProfile queued(int n, Instant submittedAt) {
        SellerProfile profile = new SellerProfile(sellerOf(n), user(n), SellerStatus.ON_REVIEW, FakeSellers.completeForm(), null, null, submittedAt,
                submittedAt.plus(REVIEW), null, 3, submittedAt.minusSeconds(600), submittedAt);
        fx.sellers.put(profile);
        return profile;
    }

    private void addDocument(SellerProfile profile) {
        UUID id = UUID.fromString("0199e0a0-0000-7000-8000-000000000901");
        fx.sellers.putDocument(new SellerDocument(id, profile.id(), SellerDocument.objectKey(profile.id(), id), "certificate.pdf", DocumentType.PDF,
                482_113, T0.minusSeconds(3600)));
    }

    private static ProblemException problem(Executable action) {
        return assertThrows(ProblemException.class, action);
    }

    private static List<Map<String, Object>> errors(ProblemException e) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        return errors;
    }

    /** Пары «указатель:код» или «параметр:код» из списка errors. */
    private static List<String> pairs(ProblemException e, String key) {
        return errors(e).stream().map(m -> m.get(key) + ":" + m.get("code")).toList();
    }

    private ResponseEntity<String> queue(String... nameValue) {
        MockHttpServletRequest request = moderator("GET", BASE);
        for (int i = 0; i < nameValue.length; i += 2) {
            request.addParameter(nameValue[i], nameValue[i + 1]);
        }
        return controller.queue(request);
    }

    private ResponseEntity<String> approve(UUID id) {
        return controller.approve(id.toString(), moderator("POST", BASE + "/" + id + "/approve"));
    }

    private ResponseEntity<String> reject(UUID id, String body) throws IOException {
        return controller.reject(id.toString(), moderatorJson("POST", BASE + "/" + id + "/reject", body));
    }

    private ResponseEntity<String> returnIt(UUID id, String body) throws IOException {
        return controller.returnForRework(id.toString(), moderatorJson("POST", BASE + "/" + id + "/return", body));
    }

    // ---------------------------------------------------------------- listSellerApplicationQueue

    @Test
    void queueAnswers200WithTheSchemaOfThePageAndNoStore() {
        SellerProfile profile = queued(1, T0.minusSeconds(60));
        addDocument(profile);

        ResponseEntity<String> response = queue();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(response.getHeaders().getContentType().toString().startsWith("application/json"));
        Map<String, Object> page = body(response);
        assertEquals(Set.of("items", "page"), page.keySet());
        Map<?, ?> info = (Map<?, ?>) page.get("page");
        assertEquals(Set.of("limit", "nextCursor"), info.keySet());
        assertEquals(20, info.get("limit"));
        assertNull(info.get("nextCursor"));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) page.get("items")).get(0);
        assertEquals(QUEUE_ITEM_FIELDS, item.keySet());
        assertEquals(profile.id().toString(), item.get("sellerId"));
        assertEquals("Студия «Лунный свет»", item.get("name"));
        assertEquals("self_employed", item.get("sellerType"));
        assertEquals(1, item.get("documentsCount"));
        assertEquals("2026-10-03T11:59:00.000Z", item.get("submittedAt"));
        assertEquals("2026-10-06T11:59:00.000Z", item.get("reviewDeadlineAt"));
        assertEquals(false, item.get("overdue"));
    }

    @Test
    void overdueApplicationIsFlaggedInTheQueue() {
        queued(1, T0.minus(REVIEW).minusSeconds(1));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) body(queue()).get("items")).get(0);
        assertEquals(true, item.get("overdue"));
    }

    @Test
    void onlyApplicationsOnReviewAreInTheQueueOldestFirst() {
        queued(2, T0.minusSeconds(100));
        queued(1, T0.minusSeconds(200));
        fx.sellers.put(FakeSellers.profile(user(3), SellerStatus.DRAFT, FakeSellers.completeForm(), T0));
        fx.sellers.put(FakeSellers.profile(user(4), SellerStatus.APPROVED, FakeSellers.completeForm(), T0));

        List<?> items = (List<?>) body(queue()).get("items");

        assertEquals(List.of(sellerOf(1).toString(), sellerOf(2).toString()), items.stream().map(i -> ((Map<?, ?>) i).get("sellerId")).toList());
    }

    @Test
    void queueWalksAllApplicationsPageByPageWithTheCursor() {
        for (int n = 1; n <= 5; n++) {
            queued(n, T0.minusSeconds(1000 - n * 10L));
        }
        Map<String, Object> first = body(queue("limit", "2"));
        String cursor = (String) ((Map<?, ?>) first.get("page")).get("nextCursor");
        assertNotNull(cursor);
        Map<String, Object> second = body(queue("limit", "2", "cursor", cursor));
        cursor = (String) ((Map<?, ?>) second.get("page")).get("nextCursor");
        assertNotNull(cursor);
        Map<String, Object> third = body(queue("limit", "2", "cursor", cursor));
        assertNull(((Map<?, ?>) third.get("page")).get("nextCursor"));

        List<Object> seen = new ArrayList<>();
        for (Map<String, Object> page : List.of(first, second, third)) {
            for (Object item : (List<?>) page.get("items")) {
                seen.add(((Map<?, ?>) item).get("sellerId"));
            }
        }
        assertEquals(List.of(1, 2, 3, 4, 5).stream().map(n -> (Object) sellerOf(n).toString()).toList(), seen);
        assertEquals(2, ((List<?>) first.get("items")).size());
        assertEquals(1, ((List<?>) third.get("items")).size());
    }

    @Test
    void invalidQueueParametersAreCollectedIntoOne422() {
        ProblemException e = problem(() -> queue("limit", "0", "cursor", "не-курсор", "color", "red"));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(Set.of("limit:out_of_range", "cursor:invalid_format", "color:unknown_value"), Set.copyOf(pairs(e, "parameter")));
        assertEquals(ProblemType.VALIDATION_FAILED, problem(() -> queue("limit", "101")).type());
        assertEquals(ProblemType.VALIDATION_FAILED, problem(() -> queue("limit", "abc")).type());
    }

    @Test
    void cursorThatIsNotOursIsRefused() {
        // Курсор разбирается, но ключей сортировки очереди в нём нет
        String foreign = PageCursor.encode(Map.of("x", "1"));
        assertEquals(List.of("cursor:invalid_format"), pairs(problem(() -> queue("cursor", foreign)), "parameter"));
        // Ключи на месте, но значения не время и не UUID
        String garbled = PageCursor.encode(new LinkedHashMap<>(Map.of("p", "вчера", "i", "42")));
        assertEquals(List.of("cursor:invalid_format"), pairs(problem(() -> queue("cursor", garbled)), "parameter"));
    }

    @Test
    void emptyQueueIsAnEmptyPage() {
        assertEquals(List.of(), body(queue()).get("items"));
    }

    // ---------------------------------------------------------------- getSellerApplicationCard

    @Test
    void cardAnswers200WithAllFieldsOfTheSchemaAndLinksToTheDocuments() {
        SellerProfile profile = queued(1, T0.minusSeconds(60));
        addDocument(profile);

        ResponseEntity<String> response = controller.card(profile.id().toString());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> card = body(response);
        assertEquals(CARD_FIELDS, card.keySet());
        assertEquals(profile.id().toString(), card.get("sellerId"));
        assertEquals(user(1).toString(), card.get("userId"));
        assertEquals("on_review", card.get("status"));
        assertEquals("self_employed", card.get("sellerType"));
        assertEquals("Студия «Лунный свет»", card.get("name"));
        assertEquals(Map.of("recipientName", "Иванов Иван Иванович", "bankName", "Банк «Пример»", "bik", "044525000",
                "accountNumber", "40817810000000000001"), card.get("payoutDetails"));
        assertEquals("Ключи к инди-играм для Steam", card.get("assortmentDescription"));
        assertEquals("2026-10-03T11:59:00.000Z", card.get("submittedAt"));
        assertEquals("2026-10-06T11:59:00.000Z", card.get("reviewDeadlineAt"));
        Map<?, ?> document = (Map<?, ?>) ((List<?>) card.get("documents")).get(0);
        assertEquals(Set.of("documentId", "fileName", "contentType", "sizeBytes", "uploadedAt", "link"), document.keySet());
        assertEquals("certificate.pdf", document.get("fileName"));
        assertEquals("application/pdf", document.get("contentType"));
        Map<?, ?> link = (Map<?, ?>) document.get("link");
        assertEquals(Set.of("url", "expiresAt"), link.keySet());
        assertTrue(((String) link.get("url")).contains(profile.id().toString()));
        assertEquals("2026-10-03T12:05:00.000Z", link.get("expiresAt"), "ссылка на 5 минут");
    }

    @Test
    void openingTheCardChangesNothing() {
        SellerProfile profile = queued(1, T0);
        controller.card(profile.id().toString());
        assertEquals(profile, fx.sellers.stored(user(1)));
        assertTrue(fx.moderationTransaction.published().isEmpty());
    }

    @Test
    void cardRejectsMalformedIdsWith400AndUnknownOrDraftApplicationsWith404() {
        for (String bad : new String[] {"123", "not-a-uuid", "0199E0A0-0000-7000-8000-000000000101", ""}) {
            assertEquals(ProblemType.BAD_REQUEST, problem(() -> controller.card(bad)).type(), bad);
        }
        assertEquals(ProblemType.NOT_FOUND, problem(() -> controller.card("0199e0a0-0000-7000-8000-000000000999")).type());
        SellerProfile draft = FakeSellers.profile(user(2), SellerStatus.DRAFT, FakeSellers.completeForm(), T0);
        fx.sellers.put(draft);
        assertEquals(ProblemType.NOT_FOUND, problem(() -> controller.card(draft.id().toString())).type());
    }

    @Test
    void decidedApplicationsStayVisibleToTheModerator() {
        SellerProfile approved = queued(5, T0.minusSeconds(60)).approve(T0);
        fx.sellers.put(approved);
        Map<String, Object> card = body(controller.card(approved.id().toString()));
        assertEquals("approved", card.get("status"));
        assertEquals("2026-10-06T11:59:00.000Z", card.get("reviewDeadlineAt"), "срок рассмотрения остаётся в записи для истории");
    }

    // ---------------------------------------------------------------- approveSellerApplication

    @Test
    void approveAnswers200WithTheApplicationOfTheSchemaAndWritesTheEventWithTheModerator() {
        SellerProfile profile = queued(1, T0.minusSeconds(60));
        addDocument(profile);
        MockHttpServletRequest request = moderator("POST", BASE + "/" + profile.id() + "/approve");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        ResponseEntity<String> response = controller.approve(profile.id().toString(), request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertNull(response.getHeaders().getETag(), "в контракте решения заголовка ETag нет");
        Map<String, Object> application = body(response);
        assertEquals(APPLICATION_FIELDS, application.keySet());
        assertEquals("approved", application.get("status"));
        assertTrue(application.containsKey("decisionReason") && application.get("decisionReason") == null);
        assertNull(application.get("rejectedAt"));
        assertNull(application.get("nextApplicationAt"));
        assertNull(application.get("reviewDeadlineAt"), "срок рассмотрения показывается, пока заявка на проверке");
        assertEquals(1, ((List<?>) application.get("documents")).size());
        assertEquals(MODERATOR.toString(), fx.moderationTransaction.published("seller.approved").get(0).data().get("moderatorId"));
        assertEquals("203.0.113.7", fx.moderationTransaction.published("audit.recorded").get(0).data().get("actorIp"));
    }

    @Test
    void auditUsesTheConnectionAddressWhenThereIsNoGatewayHeader() {
        SellerProfile profile = queued(1, T0);
        approve(profile.id());
        assertEquals("127.0.0.1", fx.moderationTransaction.published("audit.recorded").get(0).data().get("actorIp"));
    }

    @Test
    void secondDecisionIs409WithTheCurrentStatusAndWritesNothing() {
        SellerProfile profile = queued(1, T0);
        approve(profile.id());
        int written = fx.moderationTransaction.published().size();
        assertEquals(2, written, "решение и запись аудита");

        List<Executable> seconds = List.of(() -> approve(profile.id()), () -> reject(profile.id(), "{\"reason\":\"Причина\"}"),
                () -> returnIt(profile.id(), "{\"comment\":\"Комментарий\"}"));
        for (Executable second : seconds) {
            ProblemException e = problem(second);
            assertEquals(ProblemType.STATE_CONFLICT, e.type());
            assertEquals("approved", e.extensions().get("currentStatus"));
        }
        assertEquals(written, fx.moderationTransaction.published().size(), "повторное решение событий не пишет");
    }

    @Test
    void approveOfAnUnknownOrDraftApplicationIs404AndOfAMalformedIdIs400() {
        assertEquals(ProblemType.NOT_FOUND, problem(() -> approve(UUID.fromString("0199e0a0-0000-7000-8000-000000000999"))).type());
        SellerProfile draft = FakeSellers.profile(user(2), SellerStatus.DRAFT, FakeSellers.completeForm(), T0);
        fx.sellers.put(draft);
        assertEquals(ProblemType.NOT_FOUND, problem(() -> approve(draft.id())).type());
        assertEquals(ProblemType.BAD_REQUEST, problem(() -> controller.approve("nope", moderator("POST", BASE + "/nope/approve"))).type());
    }

    // ---------------------------------------------------------------- rejectSellerApplication

    @Test
    void rejectAnswers200WithTheReasonAndTheDateOfTheNextApplication() throws IOException {
        SellerProfile profile = queued(1, T0.minusSeconds(60));

        ResponseEntity<String> response = reject(profile.id(), "{\"reason\":\"  Документ не читается  \"}");

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> application = body(response);
        assertEquals(APPLICATION_FIELDS, application.keySet());
        assertEquals("rejected", application.get("status"));
        assertEquals("Документ не читается", application.get("decisionReason"), "пробелы по краям убраны");
        assertEquals("2026-10-03T12:00:00.000Z", application.get("rejectedAt"));
        assertEquals("2026-10-04T12:00:00.000Z", application.get("nextApplicationAt"), "отказ плюс 24 часа");
        assertEquals("Документ не читается", fx.moderationTransaction.published("seller.rejected").get(0).data().get("reason"));
    }

    @Test
    void reasonOfExactlyThousandCharactersIsAcceptedAndOneMoreIsNot() throws IOException {
        SellerProfile profile = queued(1, T0);
        ProblemException e = problem(() -> reject(profile.id(), "{\"reason\":\"" + "я".repeat(1001) + "\"}"));
        assertEquals(List.of("/reason:too_long"), pairs(e, "pointer"));
        assertEquals(SellerStatus.ON_REVIEW, fx.sellers.stored(user(1)).status());
        assertEquals(200, reject(profile.id(), "{\"reason\":\"" + "я".repeat(1000) + "\"}").getStatusCode().value());
    }

    @Test
    void invalidRejectBodiesAreRefusedBeforeAnythingChanges() {
        SellerProfile profile = queued(1, T0);
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("{}", "/reason:required");
        bodies.put("{\"reason\":null}", "/reason:required");
        bodies.put("{\"reason\":\"\"}", "/reason:too_short");
        bodies.put("{\"reason\":\"    \"}", "/reason:too_short");
        bodies.put("{\"reason\":5}", "/reason:invalid_format");
        bodies.put("{\"reason\":[\"a\"]}", "/reason:invalid_format");
        bodies.put("{\"reason\":\"Причина\",\"extra\":1}", "/extra:unknown_value");
        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            ProblemException e = problem(() -> reject(profile.id(), entry.getKey()));
            assertEquals(ProblemType.VALIDATION_FAILED, e.type(), entry.getKey());
            assertEquals(List.of(entry.getValue()), pairs(e, "pointer"), entry.getKey());
        }
        ProblemException wrongField = problem(() -> reject(profile.id(), "{\"comment\":\"Причина\"}"));
        assertEquals(Set.of("/comment:unknown_value", "/reason:required"), Set.copyOf(pairs(wrongField, "pointer")));
        for (String notJson : new String[] {"", "не json", "[]", "\"text\"", "{\"reason\":"}) {
            assertEquals(ProblemType.BAD_REQUEST, problem(() -> reject(profile.id(), notJson)).type(), notJson);
        }
        assertEquals(SellerStatus.ON_REVIEW, fx.sellers.stored(user(1)).status());
        assertTrue(fx.moderationTransaction.published().isEmpty());
    }

    @Test
    void rejectRequiresAJsonContentType() {
        SellerProfile profile = queued(1, T0);
        byte[] content = "{\"reason\":\"Причина\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest noType = moderator("POST", BASE + "/" + profile.id() + "/reject");
        noType.setContent(content);
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.reject(profile.id().toString(), noType)).type());
        MockHttpServletRequest text = moderator("POST", BASE + "/" + profile.id() + "/reject");
        text.setContentType("text/plain");
        text.setContent(content);
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.reject(profile.id().toString(), text)).type());
        assertEquals(SellerStatus.ON_REVIEW, fx.sellers.stored(user(1)).status());
    }

    @Test
    void rejectOfAMalformedIdIs400BeforeTheBodyIsRead() {
        assertEquals(ProblemType.BAD_REQUEST, problem(() -> controller.reject("nope", moderatorJson("POST", BASE + "/nope/reject", "не json"))).type());
    }

    // ---------------------------------------------------------------- returnSellerApplication

    @Test
    void returnAnswers200WithTheCommentAndNoPause() throws IOException {
        SellerProfile profile = queued(1, T0.minusSeconds(60));

        ResponseEntity<String> response = returnIt(profile.id(), "{\"comment\":\"Приложите свидетельство о постановке на учёт\"}");

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> application = body(response);
        assertEquals(APPLICATION_FIELDS, application.keySet());
        assertEquals("returned", application.get("status"));
        assertEquals("Приложите свидетельство о постановке на учёт", application.get("decisionReason"));
        assertNull(application.get("rejectedAt"));
        assertNull(application.get("nextApplicationAt"));
        assertEquals("Приложите свидетельство о постановке на учёт", fx.moderationTransaction.published("seller.returned").get(0).data().get("comment"));
    }

    @Test
    void invalidReturnBodiesAreRefused() {
        SellerProfile profile = queued(1, T0);
        assertEquals(List.of("/comment:required"), pairs(problem(() -> returnIt(profile.id(), "{}")), "pointer"));
        assertEquals(List.of("/comment:too_short"), pairs(problem(() -> returnIt(profile.id(), "{\"comment\":\" \"}")), "pointer"));
        assertEquals(List.of("/comment:too_long"), pairs(problem(() -> returnIt(profile.id(), "{\"comment\":\"" + "я".repeat(1001) + "\"}")), "pointer"));
        assertEquals(Set.of("/reason:unknown_value", "/comment:required"),
                Set.copyOf(pairs(problem(() -> returnIt(profile.id(), "{\"reason\":\"Причина\"}")), "pointer")));
        assertEquals(SellerStatus.ON_REVIEW, fx.sellers.stored(user(1)).status());
        assertTrue(fx.moderationTransaction.published().isEmpty());
    }
}
