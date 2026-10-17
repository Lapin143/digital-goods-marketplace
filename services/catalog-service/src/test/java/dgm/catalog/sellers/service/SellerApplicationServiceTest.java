package dgm.catalog.sellers.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.FakeTransaction;
import dgm.catalog.sellers.domain.DocumentType;
import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerSettings;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.catalog.sellers.service.SellerApplicationService.Application;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Заявка заявителя целиком, без базы и с управляемыми часами: создание, правка по версии, отправка, срок рассмотрения и пауза после
 * отказа. Паузу минимум в час не сократить параметром, поэтому «через 24 часа» проверяется подменой часов.
 */
class SellerApplicationServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");
    private static final UUID USER = FakeSellers.USER;

    private ManualClock clock;
    private FakeSellers sellers;
    private FakeTransaction transaction;
    private SellerApplicationService service;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        sellers = new FakeSellers();
        transaction = new FakeTransaction();
        service = new SellerApplicationService(sellers, transaction, clock, SellerSettings.defaults());
    }

    private static SellerDocument document(SellerProfile profile, int n, Instant at) {
        UUID id = UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 900 + n));
        return new SellerDocument(id, profile.id(), SellerDocument.objectKey(profile.id(), id), "doc" + n + ".pdf", DocumentType.PDF, 1000L + n, at);
    }

    private ProblemException failure(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    // ---------------------------------------------------------------- создание

    @Test
    void createMakesADraftOfVersionOneWithAnIdentifierAndNoDocuments() {
        Application created = service.create(USER, SellerForm.empty());
        assertEquals(SellerStatus.DRAFT, created.profile().status());
        assertEquals(1, created.profile().version());
        assertEquals(USER, created.profile().userId());
        assertEquals(7, created.profile().id().version(), "идентификатор профиля UUID версии 7");
        assertTrue(created.documents().isEmpty());
        assertEquals(created.profile(), sellers.stored(USER));
        assertEquals(1, transaction.executions());
    }

    @Test
    void createKeepsAPartialForm() {
        SellerForm partial = new SellerForm(null, "Студия", null, null, null, null, null);
        assertEquals(partial, service.create(USER, partial).profile().form());
    }

    @Test
    void secondProfileOfTheSameUserIsRefusedWithTheCurrentStatus() {
        service.create(USER, SellerForm.empty());
        ProblemException e = failure(() -> service.create(USER, SellerForm.empty()));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("draft", e.extensions().get("currentStatus"));
        assertEquals(1, sellers.profileCount());
    }

    @Test
    void anotherUserGetsHisOwnProfile() {
        service.create(USER, SellerForm.empty());
        service.create(FakeSellers.OTHER_USER, SellerForm.empty());
        assertEquals(2, sellers.profileCount());
    }

    @Test
    void lostRaceForTheUniqueIndexIsAConflictNotAServerError() {
        sellers.loseNextInsertRace = true;
        ProblemException e = failure(() -> service.create(USER, SellerForm.empty()));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("draft", e.extensions().get("currentStatus"));
    }

    // ---------------------------------------------------------------- чтение

    @Test
    void currentApplicationWithoutAProfileIsNotFound() {
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.current(USER)).type());
    }

    @Test
    void currentApplicationShowsDocumentsInUploadOrder() {
        SellerProfile profile = service.create(USER, FakeSellers.completeForm()).profile();
        sellers.putDocument(document(profile, 2, T0.plusSeconds(20))).putDocument(document(profile, 1, T0.plusSeconds(10)));
        Application current = service.current(USER);
        assertEquals(java.util.List.of("doc1.pdf", "doc2.pdf"), current.documents().stream().map(SellerDocument::fileName).toList());
    }

    @Test
    void applicationOfAnotherUserIsNotVisible() {
        service.create(FakeSellers.OTHER_USER, FakeSellers.completeForm());
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.current(USER)).type());
    }

    // ---------------------------------------------------------------- правка по версии

    @Test
    void replaceNeedsAProfile() {
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.replace(USER, 1, SellerForm.empty())).type());
    }

    @Test
    void replaceWithTheCurrentVersionSavesTheFormAndRaisesTheVersion() {
        service.create(USER, SellerForm.empty());
        clock.advance(Duration.ofMinutes(5));
        Application saved = service.replace(USER, 1, FakeSellers.completeForm());
        assertEquals(2, saved.profile().version());
        assertEquals(FakeSellers.completeForm(), saved.profile().form());
        assertEquals(T0.plusSeconds(300), saved.profile().updatedAt());
        assertEquals(T0, saved.profile().createdAt());
        assertEquals(saved.profile(), sellers.stored(USER));
    }

    @Test
    void replaceWithAStaleVersionIs412AndChangesNothing() {
        service.create(USER, SellerForm.empty());
        service.replace(USER, 1, FakeSellers.completeForm());
        SellerProfile before = sellers.stored(USER);
        ProblemException e = failure(() -> service.replace(USER, 1, SellerForm.empty()));
        assertEquals(ProblemType.VERSION_CONFLICT, e.type());
        assertEquals(before, sellers.stored(USER));
    }

    @Test
    void versionFromTheFutureIsAlsoAConflict() {
        service.create(USER, SellerForm.empty());
        assertEquals(ProblemType.VERSION_CONFLICT, failure(() -> service.replace(USER, 2, SellerForm.empty())).type());
    }

    @Test
    void statusConflictComesBeforeVersionConflict() {
        sellers.put(FakeSellers.profile(USER, SellerStatus.ON_REVIEW, FakeSellers.completeForm(), T0));
        ProblemException e = failure(() -> service.replace(USER, 99, FakeSellers.completeForm()));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("on_review", e.extensions().get("currentStatus"));
    }

    @Test
    void returnedApplicationNeedsACompleteFormAfterTheVersionIsChecked() {
        sellers.put(FakeSellers.profile(USER, SellerStatus.RETURNED, FakeSellers.completeForm(), T0));
        assertEquals(ProblemType.VERSION_CONFLICT, failure(() -> service.replace(USER, 5, SellerForm.empty())).type());
        assertEquals(ProblemType.VALIDATION_FAILED, failure(() -> service.replace(USER, 1, SellerForm.empty())).type());
        assertEquals(1, sellers.stored(USER).version(), "неудачная правка версию не меняет");
    }

    @Test
    void editingARejectedProfileReopensItAsADraft() {
        sellers.put(FakeSellers.profile(USER, SellerStatus.REJECTED, FakeSellers.completeForm(), T0));
        Application reopened = service.replace(USER, 1, FakeSellers.completeForm());
        assertEquals(SellerStatus.DRAFT, reopened.profile().status());
        assertEquals("Причина решения", reopened.profile().decisionReason());
        assertEquals(T0, reopened.profile().lastRejectedAt());
    }

    // ---------------------------------------------------------------- отправка

    @Test
    void submitNeedsAProfile() {
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.submit(USER)).type());
    }

    @Test
    void submitPutsTheApplicationInTheQueueWithADeadlineThreeDaysAhead() {
        SellerProfile profile = service.create(USER, FakeSellers.completeForm()).profile();
        sellers.putDocument(document(profile, 1, T0));
        clock.advance(Duration.ofHours(1));
        Application submitted = service.submit(USER);
        assertEquals(SellerStatus.ON_REVIEW, submitted.profile().status());
        assertEquals(T0.plusSeconds(3600), submitted.profile().submittedAt());
        assertEquals(T0.plusSeconds(3600).plus(Duration.ofDays(3)), submitted.profile().reviewDeadlineAt());
        assertEquals(2, submitted.profile().version());
        assertEquals(1, submitted.documents().size());
        assertEquals(submitted.profile(), sellers.stored(USER));
    }

    @Test
    void fastTimeProfileGivesTenSecondsForReview() {
        service = new SellerApplicationService(sellers, transaction, clock,
                new SellerSettings(Duration.ofHours(24), Duration.ofSeconds(10), Duration.ofMinutes(5)));
        SellerProfile profile = service.create(USER, FakeSellers.completeForm()).profile();
        sellers.putDocument(document(profile, 1, T0));
        assertEquals(T0.plusSeconds(10), service.submit(USER).profile().reviewDeadlineAt());
    }

    @Test
    void submitWithoutADocumentOrWithAnIncompleteFormIs422AndKeepsTheDraft() {
        service.create(USER, FakeSellers.completeForm());
        ProblemException e = failure(() -> service.submit(USER));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(SellerStatus.DRAFT, sellers.stored(USER).status());
        assertEquals(1, sellers.stored(USER).version());
    }

    @Test
    void secondSubmitIsAStateConflictBecauseTheApplicationIsAlreadyInTheQueue() {
        SellerProfile profile = service.create(USER, FakeSellers.completeForm()).profile();
        sellers.putDocument(document(profile, 1, T0));
        service.submit(USER);
        ProblemException e = failure(() -> service.submit(USER));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("on_review", e.extensions().get("currentStatus"));
    }

    @Test
    void returnedApplicationIsResubmittedWithADeadlineCountedAgain() {
        SellerProfile returned = FakeSellers.profile(USER, SellerStatus.RETURNED, FakeSellers.completeForm(), T0);
        sellers.put(returned).putDocument(document(returned, 1, T0));
        clock.advance(Duration.ofDays(10));
        Application resubmitted = service.submit(USER);
        assertEquals(SellerStatus.ON_REVIEW, resubmitted.profile().status());
        assertEquals(T0.plus(Duration.ofDays(13)), resubmitted.profile().reviewDeadlineAt());
        assertNull(resubmitted.profile().decisionReason());
    }

    // ---------------------------------------------------------------- пауза после отказа, подмена часов

    @Test
    void pauseOfTwentyFourHoursAfterARejectionIsCheckedAtSubmissionByTheClock() {
        SellerProfile rejected = FakeSellers.profile(USER, SellerStatus.REJECTED, FakeSellers.completeForm(), T0);
        sellers.put(rejected).putDocument(document(rejected, 1, T0));

        // Исправлять можно сразу
        assertEquals(SellerStatus.DRAFT, service.replace(USER, 1, FakeSellers.completeForm()).profile().status());

        // Отправить нельзя, пока не прошло 24 часа: заявитель узнаёт, когда
        clock.set(T0.plus(Duration.ofHours(24)).minusSeconds(1));
        ProblemException early = failure(() -> service.submit(USER));
        assertEquals(ProblemType.APPLICATION_PAUSE_ACTIVE, early.type());
        assertEquals("2026-10-04T12:00:00.000Z", early.extensions().get("retryAfterAt"));
        assertEquals(SellerStatus.DRAFT, sellers.stored(USER).status(), "профиль остаётся черновиком");

        // Ровно через 24 часа заявка принимается
        clock.set(T0.plus(Duration.ofHours(24)));
        Application submitted = service.submit(USER);
        assertEquals(SellerStatus.ON_REVIEW, submitted.profile().status());
        assertEquals(clock.instant().plus(Duration.ofDays(3)), submitted.profile().reviewDeadlineAt());
    }

    @Test
    void pauseParameterChangesTheMoment() {
        service = new SellerApplicationService(sellers, transaction, clock,
                new SellerSettings(Duration.ofHours(1), Duration.ofDays(3), Duration.ofMinutes(5)));
        SellerProfile draft = new SellerProfile(FakeSellers.profile(USER, SellerStatus.DRAFT, FakeSellers.completeForm(), T0).id(), USER,
                SellerStatus.DRAFT, FakeSellers.completeForm(), "Причина", T0, T0, null, null, 2, T0, T0);
        sellers.put(draft).putDocument(document(draft, 1, T0));
        clock.set(T0.plus(Duration.ofMinutes(59)));
        assertEquals(ProblemType.APPLICATION_PAUSE_ACTIVE, failure(() -> service.submit(USER)).type());
        clock.set(T0.plus(Duration.ofHours(1)));
        assertNotNull(service.submit(USER));
    }

    @Test
    void applicationOfAProfileNeverRejectedIsNotHeldByAnyPause() {
        SellerProfile profile = service.create(USER, FakeSellers.completeForm()).profile();
        sellers.putDocument(document(profile, 1, T0));
        assertEquals(SellerStatus.ON_REVIEW, service.submit(USER).profile().status());
    }

    @Test
    void settingsAreExposedForTheViews() {
        assertEquals(SellerSettings.defaults(), service.settings());
    }
}
