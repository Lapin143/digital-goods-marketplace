package dgm.catalog.sellers.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.FakeStorage;
import dgm.catalog.sellers.FakeTransaction;
import dgm.catalog.sellers.FakeTransaction.Published;
import dgm.catalog.sellers.domain.DocumentType;
import dgm.catalog.sellers.domain.Moderator;
import dgm.catalog.sellers.domain.ReviewQueueItem;
import dgm.catalog.sellers.domain.ReviewQueuePage;
import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerSettings;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.catalog.sellers.service.SellerApplicationService.Application;
import dgm.catalog.sellers.service.SellerModerationService.Card;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Решения модератора и очередь без базы и с управляемыми часами: каждое решение пишет изменение профиля, событие и запись аудита вместе,
 * повторное решение отклоняется 409, черновик для модератора не существует, очередь идёт от старых заявок к новым.
 */
@Tag("TC-208")
@Tag("TC-210")
@Tag("TC-211")
@Tag("TC-213")
@Tag("TC-214")
@Tag("TC-215")
@Tag("TC-811")
class SellerModerationServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");
    private static final Duration REVIEW = Duration.ofDays(3);
    private static final UUID MODERATOR_ID = UUID.fromString("5d3b1f79-8c2e-4a60-b4d8-6e0a2c4f1b37");
    private static final Moderator MODERATOR = new Moderator(MODERATOR_ID, "203.0.113.7");
    private static final Set<String> APPROVED_FIELDS = Set.of("sellerId", "userId", "sellerType", "moderatorId");
    private static final Set<String> REJECTED_FIELDS = Set.of("sellerId", "userId", "moderatorId", "reason", "nextApplicationAt");
    private static final Set<String> RETURNED_FIELDS = Set.of("sellerId", "userId", "moderatorId", "comment");
    private static final Set<String> AUDIT_FIELDS = Set.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes",
            "actorIp", "actorIpHashed", "correlationId");

    private ManualClock clock;
    private FakeSellers sellers;
    private FakeTransaction transaction;
    private FakeStorage storage;
    private SellerModerationService service;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        sellers = new FakeSellers();
        transaction = new FakeTransaction();
        storage = new FakeStorage(clock);
        service = new SellerModerationService(sellers, transaction, storage, clock, SellerSettings.defaults());
    }

    private static UUID user(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-4000-8000-%012d", n));
    }

    private static UUID sellerOf(int n) {
        return UUID.nameUUIDFromBytes(("profile-" + user(n)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Заявка на проверке, поданная в момент {@code submittedAt}; идентификатор профиля выводится из номера пользователя. */
    private SellerProfile queued(int n, Instant submittedAt) {
        SellerProfile profile = new SellerProfile(sellerOf(n), user(n), SellerStatus.ON_REVIEW, FakeSellers.completeForm(), null, null, submittedAt,
                submittedAt.plus(REVIEW), null, 3, submittedAt.minusSeconds(600), submittedAt);
        sellers.put(profile);
        return profile;
    }

    private SellerProfile stored(int n) {
        return sellers.stored(user(n));
    }

    private SellerDocument documentOf(SellerProfile profile, int n) {
        UUID id = UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 900 + n));
        SellerDocument document = new SellerDocument(id, profile.id(), SellerDocument.objectKey(profile.id(), id), "doc" + n + ".pdf",
                DocumentType.PDF, 1000L + n, T0.minusSeconds(1000 - n));
        sellers.putDocument(document);
        return document;
    }

    private static ProblemException failure(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    private static List<String> changeSummary(Map<String, Object> audit) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) audit.get("changes");
        return changes.stream().map(c -> c.get("field") + ":" + c.get("before") + ">" + c.get("after")).toList();
    }

    // ---------------------------------------------------------------- одобрить (T3)

    @Test
    void approveWritesTheStatusTheEventAndTheAuditRecordTogether() {
        SellerProfile before = queued(1, T0.minusSeconds(3600));
        documentOf(before, 1);
        clock.advance(Duration.ofMinutes(5));

        Application result = service.approve(MODERATOR, before.id());

        assertEquals(SellerStatus.APPROVED, result.profile().status());
        assertEquals(before.version() + 1, result.profile().version());
        assertEquals(clock.instant(), result.profile().updatedAt());
        assertEquals(1, result.documents().size());
        assertEquals(result.profile(), stored(1));

        List<Published> events = transaction.published();
        assertEquals(2, events.size());
        Published decision = events.get(0);
        assertEquals("catalog.events", decision.topic());
        assertEquals("SellerProfile", decision.aggregateType());
        assertEquals(before.id().toString(), decision.aggregateId());
        assertEquals("seller.approved", decision.eventType());
        assertEquals(1, decision.schemaVersion());
        assertEquals(APPROVED_FIELDS, decision.data().keySet());
        assertEquals(before.id().toString(), decision.data().get("sellerId"));
        assertEquals(user(1).toString(), decision.data().get("userId"));
        assertEquals("self_employed", decision.data().get("sellerType"));
        assertEquals(MODERATOR_ID.toString(), decision.data().get("moderatorId"));

        Published audit = events.get(1);
        assertEquals("audit.events", audit.topic());
        assertEquals("AuditRecord", audit.aggregateType());
        assertEquals(before.id().toString(), audit.aggregateId());
        assertEquals("audit.recorded", audit.eventType());
        assertEquals(AUDIT_FIELDS, audit.data().keySet());
        assertEquals("seller.approved", audit.data().get("action"));
        assertEquals("moderator", audit.data().get("actorRole"));
        assertEquals(MODERATOR_ID.toString(), audit.data().get("actorId"));
        assertEquals("203.0.113.7", audit.data().get("actorIp"));
        assertEquals(false, audit.data().get("actorIpHashed"));
        assertEquals("seller", audit.data().get("objectType"));
        assertEquals(before.id().toString(), audit.data().get("objectId"));
        assertEquals("2026-10-03T12:05:00.000Z", audit.data().get("occurredAt"));
        assertEquals(List.of("status:on_review>approved"), changeSummary(audit.data()));
    }

    // ---------------------------------------------------------------- отклонить (T4)

    @Test
    void rejectStoresTheReasonStartsThePauseAndTellsWhenANewApplicationIsPossible() {
        SellerProfile before = queued(1, T0);
        clock.advance(Duration.ofHours(2));
        Instant decidedAt = clock.instant();

        Application result = service.reject(MODERATOR, before.id(), "Документ не читается");

        assertEquals(SellerStatus.REJECTED, result.profile().status());
        assertEquals("Документ не читается", result.profile().decisionReason());
        assertEquals(decidedAt, result.profile().lastRejectedAt());
        Published decision = transaction.published("seller.rejected").get(0);
        assertEquals(REJECTED_FIELDS, decision.data().keySet());
        assertEquals("Документ не читается", decision.data().get("reason"));
        assertEquals("2026-10-04T14:00:00.000Z", decision.data().get("nextApplicationAt"), "время отказа плюс 24 часа");
        assertEquals(MODERATOR_ID.toString(), decision.data().get("moderatorId"));

        Published audit = transaction.published("audit.recorded").get(0);
        assertEquals("seller.rejected", audit.data().get("action"));
        assertEquals(List.of("status:on_review>rejected", "decisionReason:null>Документ не читается"), changeSummary(audit.data()));
    }

    @Test
    void thePauseFollowsTheSettingOfThePlatform() {
        service = new SellerModerationService(sellers, transaction, storage, clock,
                new SellerSettings(Duration.ofDays(2), SellerSettings.defaults().reviewPeriod(), SellerSettings.defaults().documentLinkTtl()));
        SellerProfile before = queued(1, T0);
        service.reject(MODERATOR, before.id(), "Причина");
        assertEquals("2026-10-05T12:00:00.000Z", transaction.published("seller.rejected").get(0).data().get("nextApplicationAt"));
    }

    // ---------------------------------------------------------------- вернуть на доработку (T5)

    @Test
    void returnStoresTheCommentAndDoesNotStartAPause() {
        SellerProfile before = queued(1, T0);

        Application result = service.returnForRework(MODERATOR, before.id(), "Приложите свидетельство о постановке на учёт");

        assertEquals(SellerStatus.RETURNED, result.profile().status());
        assertEquals("Приложите свидетельство о постановке на учёт", result.profile().decisionReason());
        assertNull(result.profile().lastRejectedAt());
        Published decision = transaction.published("seller.returned").get(0);
        assertEquals(RETURNED_FIELDS, decision.data().keySet());
        assertEquals("Приложите свидетельство о постановке на учёт", decision.data().get("comment"));
        Published audit = transaction.published("audit.recorded").get(0);
        assertEquals("seller.returned", audit.data().get("action"));
        assertEquals(List.of("status:on_review>returned", "decisionReason:null>Приложите свидетельство о постановке на учёт"),
                changeSummary(audit.data()));
    }

    // ---------------------------------------------------------------- повторное решение и недоступные заявки

    private List<Function<UUID, Application>> allDecisions() {
        return List.of(id -> service.approve(MODERATOR, id), id -> service.reject(MODERATOR, id, "Причина"),
                id -> service.returnForRework(MODERATOR, id, "Комментарий"));
    }

    @Test
    void secondDecisionOnTheSameApplicationIs409WithTheCurrentStatusAndWritesNothing() {
        SellerProfile before = queued(1, T0);
        service.approve(MODERATOR, before.id());
        SellerProfile decided = stored(1);
        int writtenEvents = transaction.published().size();

        for (Function<UUID, Application> decision : allDecisions()) {
            ProblemException e = failure(() -> decision.apply(before.id()));
            assertEquals(ProblemType.STATE_CONFLICT, e.type());
            assertEquals("approved", e.extensions().get("currentStatus"));
        }
        assertEquals(decided, stored(1), "профиль не изменился");
        assertEquals(writtenEvents, transaction.published().size(), "новых событий нет");
    }

    @Test
    void everyDecisionIsRefusedForAnApplicationThatIsNotWithTheModerator() {
        for (SellerStatus status : List.of(SellerStatus.APPROVED, SellerStatus.REJECTED, SellerStatus.RETURNED)) {
            SellerProfile profile = FakeSellers.profile(user(7), status, FakeSellers.completeForm(), T0);
            sellers.put(profile);
            for (Function<UUID, Application> decision : allDecisions()) {
                ProblemException e = failure(() -> decision.apply(profile.id()));
                assertEquals(ProblemType.STATE_CONFLICT, e.type(), status.code());
                assertEquals(status.code(), e.extensions().get("currentStatus"));
            }
            assertEquals(profile, stored(7));
        }
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void draftsAndUnknownApplicationsDoNotExistForTheModerator() {
        SellerProfile draft = FakeSellers.profile(user(1), SellerStatus.DRAFT, FakeSellers.completeForm(), T0);
        SellerProfile reopened = new SellerProfile(sellerOf(2), user(2), SellerStatus.DRAFT, FakeSellers.completeForm(), "Причина", T0,
                T0.minusSeconds(900), T0, null, 6, T0, T0);
        sellers.put(draft).put(reopened);
        for (UUID id : List.of(draft.id(), reopened.id(), UUID.fromString("0199e0a0-0000-7000-8000-000000000999"))) {
            for (Function<UUID, Application> decision : allDecisions()) {
                assertEquals(ProblemType.NOT_FOUND, failure(() -> decision.apply(id)).type());
            }
            assertEquals(ProblemType.NOT_FOUND, failure(() -> service.card(MODERATOR, id)).type());
        }
        assertTrue(transaction.published().isEmpty());
        assertEquals(SellerStatus.DRAFT, stored(1).status());
        assertEquals(draft.version(), stored(1).version());
    }

    @Test
    void failureAfterTheDecisionEventLeavesNoEventAtAll() {
        SellerProfile before = queued(1, T0);
        Moderator withBrokenAddress = new Moderator(MODERATOR_ID, "x".repeat(200));

        assertThrows(IllegalArgumentException.class, () -> service.approve(withBrokenAddress, before.id()));

        assertTrue(transaction.published().isEmpty(), "событие решения и аудит фиксируются вместе или не фиксируются совсем");
    }

    // ---------------------------------------------------------------- очередь

    @Test
    void queueShowsOnlyApplicationsOnReviewOldestFirst() {
        queued(3, T0.minusSeconds(100));
        queued(1, T0.minusSeconds(300));
        queued(2, T0.minusSeconds(200));
        sellers.put(FakeSellers.profile(user(4), SellerStatus.DRAFT, FakeSellers.completeForm(), T0));
        sellers.put(FakeSellers.profile(user(5), SellerStatus.APPROVED, FakeSellers.completeForm(), T0));
        documentOf(stored(1), 1);
        documentOf(stored(1), 2);

        ReviewQueuePage page = service.queue(20, Optional.empty());

        assertEquals(List.of(sellerOf(1), sellerOf(2), sellerOf(3)), page.items().stream().map(ReviewQueueItem::sellerId).toList());
        assertEquals(20, page.limit());
        assertEquals(Optional.empty(), page.next());
        assertEquals(clock.instant(), page.at());
        ReviewQueueItem first = page.items().get(0);
        assertEquals("Студия «Лунный свет»", first.name());
        assertEquals(2, first.documentsCount());
        assertEquals(T0.minusSeconds(300), first.submittedAt());
        assertEquals(T0.minusSeconds(300).plus(REVIEW), first.reviewDeadlineAt());
    }

    @Test
    void queuePagesDoNotSkipOrRepeatApplications() {
        for (int n = 1; n <= 5; n++) {
            queued(n, T0.minusSeconds(1000 - n * 10L));
        }
        ReviewQueuePage first = service.queue(2, Optional.empty());
        assertEquals(List.of(sellerOf(1), sellerOf(2)), first.items().stream().map(ReviewQueueItem::sellerId).toList());
        assertTrue(first.next().isPresent());
        ReviewQueuePage second = service.queue(2, first.next());
        assertEquals(List.of(sellerOf(3), sellerOf(4)), second.items().stream().map(ReviewQueueItem::sellerId).toList());
        ReviewQueuePage last = service.queue(2, second.next());
        assertEquals(List.of(sellerOf(5)), last.items().stream().map(ReviewQueueItem::sellerId).toList());
        assertEquals(Optional.empty(), last.next());
    }

    @Test
    void applicationsSubmittedAtTheSameMomentKeepAStableOrderByIdentifier() {
        for (int n = 1; n <= 4; n++) {
            queued(n, T0.minusSeconds(60));
        }
        List<UUID> expected = List.of(sellerOf(1), sellerOf(2), sellerOf(3), sellerOf(4)).stream().sorted().toList();
        ReviewQueuePage first = service.queue(3, Optional.empty());
        ReviewQueuePage second = service.queue(3, first.next());
        List<UUID> seen = new java.util.ArrayList<>(first.items().stream().map(ReviewQueueItem::sellerId).toList());
        seen.addAll(second.items().stream().map(ReviewQueueItem::sellerId).toList());
        assertEquals(expected, seen);
    }

    @Test
    void emptyQueueIsAnEmptyPage() {
        ReviewQueuePage page = service.queue(20, Optional.empty());
        assertTrue(page.items().isEmpty());
        assertEquals(Optional.empty(), page.next());
    }

    @Test
    void overdueFlagIsComputedFromTheDeadlineAtTheMomentOfReading() {
        queued(1, T0.minus(REVIEW).plusSeconds(60));
        ReviewQueuePage early = service.queue(20, Optional.empty());
        assertFalse(early.items().get(0).overdue(early.at()));
        clock.advance(Duration.ofSeconds(59));
        ReviewQueuePage justBefore = service.queue(20, Optional.empty());
        assertFalse(justBefore.items().get(0).overdue(justBefore.at()), "за секунду до срока");
        clock.advance(Duration.ofSeconds(1));
        ReviewQueuePage page = service.queue(20, Optional.empty());
        assertTrue(page.items().get(0).overdue(page.at()), "срок вышел ровно сейчас");
    }

    // ---------------------------------------------------------------- карточка

    @Test
    void cardShowsTheFormAndShortLivedLinksToAllDocumentsAndChangesNothing() {
        SellerProfile profile = queued(1, T0.minusSeconds(60));
        SellerDocument first = documentOf(profile, 1);
        SellerDocument second = documentOf(profile, 2);

        Card card = service.card(MODERATOR, profile.id());

        assertEquals(profile, card.profile());
        assertEquals(List.of(first, second), card.documents().stream().map(SellerModerationService.CardDocument::document).toList());
        assertEquals(Duration.ofSeconds(300), storage.lastTtl);
        for (SellerModerationService.CardDocument item : card.documents()) {
            assertEquals(clock.instant().plusSeconds(300), item.link().expiresAt());
            assertTrue(item.link().url().toString().contains(item.document().objectKey()));
        }
        assertEquals(profile, stored(1), "открытие карточки заявку не меняет");
        assertEquals(List.of("audit.recorded", "audit.recorded"), transaction.published().stream().map(Published::eventType).toList(),
                "кроме записей об открытии документов, событий нет");
    }

    /** Чтение документа это действие (Р-166): по записи на каждую выданную ссылку, исполнитель сам модератор, содержимого и ссылки в записи нет. */
    @Test
    void everyIssuedLinkIsRecordedAsTheModeratorOpeningTheDocument() {
        SellerProfile profile = queued(1, T0.minusSeconds(60));
        SellerDocument first = documentOf(profile, 1);
        SellerDocument second = documentOf(profile, 2);

        service.card(MODERATOR, profile.id());

        List<Published> records = transaction.published("audit.recorded");
        assertEquals(2, records.size());
        List<String> opened = new ArrayList<>();
        for (Published record : records) {
            assertEquals("audit.events", record.topic());
            assertEquals("AuditRecord", record.aggregateType());
            assertEquals(profile.id().toString(), record.aggregateId(), "ключ записи Kafka тот же, что у решений по этой заявке");
            assertEquals(AUDIT_FIELDS, record.data().keySet());
            assertEquals("seller.document_opened", record.data().get("action"));
            assertEquals("seller", record.data().get("objectType"));
            assertEquals(profile.id().toString(), record.data().get("objectId"));
            assertEquals(MODERATOR_ID.toString(), record.data().get("actorId"));
            assertEquals("moderator", record.data().get("actorRole"));
            assertEquals("203.0.113.7", record.data().get("actorIp"));
            assertEquals(false, record.data().get("actorIpHashed"));
            assertEquals("2026-10-03T12:00:00.000Z", record.data().get("occurredAt"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> changes = (List<Map<String, Object>>) record.data().get("changes");
            assertEquals(1, changes.size());
            assertEquals("documentId", changes.get(0).get("field"));
            assertEquals(false, changes.get(0).get("personal"));
            assertNull(changes.get(0).get("before"));
            opened.add((String) changes.get(0).get("after"));
            String text = record.data().toString();
            assertFalse(text.contains(first.fileName()) || text.contains(second.fileName()), "имени файла в записи нет");
            assertFalse(text.contains("X-Amz") || text.contains("seller-documents/"), "ссылки в записи нет");
        }
        assertEquals(List.of(first.id().toString(), second.id().toString()), opened, "по одной записи на документ, в порядке карточки");
    }

    /** Не удалось записать открытие: 503 и ни одной ссылки, анкета тоже не отдаётся. */
    @Test
    void ifTheOpeningCannotBeRecordedThereAreNoLinksAndTheAnswerIsFiveHundredAndThree() {
        SellerProfile profile = queued(1, T0.minusSeconds(60));
        documentOf(profile, 1);
        transaction.failPublishWith(new IllegalStateException("Outbox недоступен"));

        ProblemException e = failure(() -> service.card(MODERATOR, profile.id()));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertTrue(e.headers().containsKey("Retry-After"));
        assertEquals(0, storage.presigned, "ссылки не подписывались");
        assertTrue(transaction.published().isEmpty());
        assertEquals(profile, stored(1));
    }

    @Test
    void failedRecordOfTheSecondDocumentTakesBackTheFirstOneToo() {
        SellerProfile profile = queued(1, T0.minusSeconds(60));
        documentOf(profile, 1);
        documentOf(profile, 2);
        Moderator brokenAddress = new Moderator(MODERATOR_ID, "x".repeat(200));

        assertThrows(IllegalArgumentException.class, () -> service.card(brokenAddress, profile.id()));

        assertTrue(transaction.published().isEmpty(), "записи об открытии фиксируются все вместе или ни одной");
        assertEquals(0, storage.presigned);
    }

    @Test
    void cardWithoutDocumentsRecordsNothingBecauseNothingWasOpened() {
        SellerProfile profile = queued(1, T0);
        service.card(MODERATOR, profile.id());
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void openingTheSameCardAgainIsAnotherOpeningAndAnotherRecord() {
        SellerProfile profile = queued(1, T0);
        documentOf(profile, 1);
        service.card(MODERATOR, profile.id());
        service.card(MODERATOR, profile.id());
        assertEquals(2, transaction.published("audit.recorded").size());
    }

    @Test
    void cardOfADecidedApplicationIsStillAvailableForTheHistory() {
        SellerProfile profile = queued(1, T0);
        service.reject(MODERATOR, profile.id(), "Причина");
        Card card = service.card(MODERATOR, profile.id());
        assertEquals(SellerStatus.REJECTED, card.profile().status());
        assertEquals("Причина", card.profile().decisionReason());
    }

    @Test
    void cardWithoutDocumentsHasAnEmptyList() {
        SellerProfile profile = queued(1, T0);
        assertEquals(List.of(), service.card(MODERATOR, profile.id()).documents());
    }

    @Test
    void settingsAreTheOnesTheServiceWasBuiltWith() {
        assertEquals(SellerSettings.defaults(), service.settings());
    }

    @Test
    void theFormOfTheStoredProfileIsNotCutByTheDecision() {
        SellerForm form = queued(1, T0).form();
        service.approve(MODERATOR, sellerOf(1));
        assertEquals(form, stored(1).form());
    }
}
