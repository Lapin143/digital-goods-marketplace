package dgm.catalog.sellers.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Поведение профиля по SM-05 без базы и без часов системы: время передаётся параметром. Каждый статус проверяется отдельно для правки
 * анкеты и для отправки заявки.
 */
@Tag("TC-201")
@Tag("TC-203")
@Tag("TC-204")
@Tag("TC-205")
@Tag("TC-208")
@Tag("TC-209")
@Tag("TC-211")
@Tag("TC-214")
@Tag("TC-215")
@Tag("TC-216")
class SellerProfileTest {

    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");
    private static final Duration PAUSE = Duration.ofHours(24);
    private static final Duration REVIEW = Duration.ofDays(3);
    private static final UUID ID = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final SellerForm FULL = FakeSellers.completeForm();

    private static SellerProfile profile(SellerStatus status, SellerForm form, Instant lastRejectedAt) {
        return new SellerProfile(ID, USER, status, form, status == SellerStatus.REJECTED || status == SellerStatus.RETURNED ? "Причина" : null,
                lastRejectedAt, null, null, null, 3, T0.minusSeconds(3600), T0.minusSeconds(60));
    }

    private static List<String> errorPointers(ProblemException e) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        return errors.stream().map(error -> (String) error.get("pointer")).toList();
    }

    // ---------------------------------------------------------------- создание (T1)

    @Test
    void newProfileIsADraftOfVersionOneAndNotInTheQueue() {
        SellerProfile created = SellerProfile.create(ID, USER, SellerForm.empty(), T0);
        assertEquals(SellerStatus.DRAFT, created.status());
        assertEquals(1, created.version());
        assertEquals(T0, created.createdAt());
        assertEquals(T0, created.updatedAt());
        assertNull(created.submittedAt());
        assertNull(created.reviewDeadlineAt());
        assertNull(created.decisionReason());
        assertNull(created.lastRejectedAt());
    }

    // ---------------------------------------------------------------- правка анкеты

    @ParameterizedTest
    @EnumSource(value = SellerStatus.class, names = {"ON_REVIEW", "APPROVED", "BLOCKED"})
    void formCannotBeReplacedWhenTheApplicationIsWithTheModeratorOrDecided(SellerStatus status) {
        ProblemException e = assertThrows(ProblemException.class, () -> profile(status, FULL, null).replaceForm(FULL, T0));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals(status.code(), e.extensions().get("currentStatus"));
    }

    @Test
    void draftKeepsAnyFormAndVersionGrows() {
        SellerForm partial = new SellerForm(SellerType.SELF_EMPLOYED, "Студия", null, null, null, null, null);
        SellerProfile saved = profile(SellerStatus.DRAFT, FULL, null).replaceForm(partial, T0);
        assertEquals(SellerStatus.DRAFT, saved.status());
        assertEquals(partial, saved.form(), "PUT заменяет анкету целиком: пропущенные поля очищаются");
        assertEquals(4, saved.version());
        assertEquals(T0, saved.updatedAt());
        assertEquals(T0.minusSeconds(3600), saved.createdAt());
    }

    @Test
    void rejectedProfileReturnsToDraftOnEditAndKeepsTheReasonAndTheRejectionDate() {
        Instant rejectedAt = T0.minusSeconds(600);
        SellerProfile reopened = profile(SellerStatus.REJECTED, FULL, rejectedAt).replaceForm(FULL, T0);
        assertEquals(SellerStatus.DRAFT, reopened.status(), "SM-05/T6");
        assertEquals("Причина", reopened.decisionReason(), "причина отказа остаётся видна до новой подачи");
        assertEquals(rejectedAt, reopened.lastRejectedAt(), "пауза считается от прежнего отказа");
        assertEquals(4, reopened.version());
    }

    @Test
    void rejectedProfileMayBeEditedDuringThePauseThePauseOnlyBlocksSubmission() {
        SellerProfile reopened = profile(SellerStatus.REJECTED, FULL, T0.minusSeconds(10)).replaceForm(FULL, T0);
        assertEquals(SellerStatus.DRAFT, reopened.status());
    }

    @Test
    void returnedProfileNeedsACompleteFormOnEveryEdit() {
        SellerForm partial = new SellerForm(SellerType.SELF_EMPLOYED, "Студия", null, "Банк", null, null, null);
        ProblemException e = assertThrows(ProblemException.class, () -> profile(SellerStatus.RETURNED, FULL, null).replaceForm(partial, T0));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(List.of("/payoutDetails/recipientName", "/payoutDetails/bik", "/payoutDetails/accountNumber", "/assortmentDescription"),
                errorPointers(e));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        assertTrue(errors.stream().allMatch(error -> "required".equals(error.get("code"))));

        SellerProfile saved = profile(SellerStatus.RETURNED, FULL, null).replaceForm(FULL, T0);
        assertEquals(SellerStatus.RETURNED, saved.status(), "правка не отправляет заявку");
        assertEquals("Причина", saved.decisionReason(), "комментарий доработки остаётся");
    }

    @ParameterizedTest
    @EnumSource(SellerStatus.class)
    void documentsAreEditableExactlyWhereTheFormIs(SellerStatus status) {
        Set<SellerStatus> editable = EnumSet.of(SellerStatus.DRAFT, SellerStatus.RETURNED, SellerStatus.REJECTED);
        SellerProfile profile = profile(status, FULL, null);
        if (editable.contains(status)) {
            profile.requireEditable();
        } else {
            ProblemException e = assertThrows(ProblemException.class, profile::requireEditable);
            assertEquals(ProblemType.STATE_CONFLICT, e.type());
            assertEquals(status.code(), e.extensions().get("currentStatus"));
        }
    }

    // ---------------------------------------------------------------- отправка (T2, T7)

    @Test
    void draftIsSubmittedToTheQueueWithADeadlineThreeDaysAhead() {
        SellerProfile submitted = profile(SellerStatus.DRAFT, FULL, null).submit(1, T0, PAUSE, REVIEW);
        assertEquals(SellerStatus.ON_REVIEW, submitted.status(), "SM-05/T2");
        assertEquals(T0, submitted.submittedAt());
        assertEquals(T0.plus(Duration.ofDays(3)), submitted.reviewDeadlineAt());
        assertEquals(4, submitted.version());
        assertEquals(T0, submitted.updatedAt());
        assertNull(submitted.overdueMarkedAt());
        assertEquals(FULL, submitted.form());
    }

    @Test
    void returnedApplicationIsResubmittedWithADeadlineCountedAgain() {
        SellerProfile returned = new SellerProfile(ID, USER, SellerStatus.RETURNED, FULL, "Добавьте скан", null, T0.minusSeconds(86_400),
                T0.minusSeconds(86_400).plus(REVIEW), T0.minusSeconds(100), 5, T0.minusSeconds(90_000), T0.minusSeconds(60));
        SellerProfile resubmitted = returned.submit(2, T0, PAUSE, REVIEW);
        assertEquals(SellerStatus.ON_REVIEW, resubmitted.status(), "SM-05/T7");
        assertEquals(T0, resubmitted.submittedAt());
        assertEquals(T0.plus(REVIEW), resubmitted.reviewDeadlineAt(), "срок отсчитывается заново");
        assertNull(resubmitted.overdueMarkedAt(), "пометка просрочки прежней подачи сброшена");
        assertNull(resubmitted.decisionReason(), "комментарий прежнего решения очищается");
    }

    @Test
    void reviewPeriodIsAParameterNotAConstant() {
        SellerProfile submitted = profile(SellerStatus.DRAFT, FULL, null).submit(1, T0, PAUSE, Duration.ofSeconds(10));
        assertEquals(T0.plusSeconds(10), submitted.reviewDeadlineAt());
    }

    @ParameterizedTest
    @EnumSource(value = SellerStatus.class, names = {"ON_REVIEW", "APPROVED", "BLOCKED"})
    void secondApplicationIsNotAcceptedWhileOneIsActiveOrApproved(SellerStatus status) {
        ProblemException e = assertThrows(ProblemException.class, () -> profile(status, FULL, null).submit(1, T0, PAUSE, REVIEW));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals(status.code(), e.extensions().get("currentStatus"));
    }

    @Test
    void submissionNeedsEveryFieldAndADocument() {
        ProblemException noDocument = assertThrows(ProblemException.class, () -> profile(SellerStatus.DRAFT, FULL, null).submit(0, T0, PAUSE, REVIEW));
        assertEquals(ProblemType.VALIDATION_FAILED, noDocument.type());
        assertEquals(List.of("/documents"), errorPointers(noDocument));

        SellerForm partial = new SellerForm(SellerType.SELF_EMPLOYED, "Студия", null, null, null, null, null);
        ProblemException all = assertThrows(ProblemException.class, () -> profile(SellerStatus.DRAFT, partial, null).submit(0, T0, PAUSE, REVIEW));
        assertEquals(List.of("/payoutDetails/recipientName", "/payoutDetails/bankName", "/payoutDetails/bik", "/payoutDetails/accountNumber",
                "/assortmentDescription", "/documents"), errorPointers(all));
    }

    @Test
    void returnedApplicationNeedsADocumentToo() {
        ProblemException e = assertThrows(ProblemException.class, () -> profile(SellerStatus.RETURNED, FULL, null).submit(0, T0, PAUSE, REVIEW));
        assertEquals(List.of("/documents"), errorPointers(e));
    }

    // ---------------------------------------------------------------- пауза после отказа (FT-2.4, U14)

    @Test
    void pauseIsMeasuredFromTheRejection() {
        Instant rejectedAt = T0;
        SellerProfile draft = profile(SellerStatus.DRAFT, FULL, rejectedAt);
        assertEquals(Optional.of(rejectedAt.plus(PAUSE)), draft.nextApplicationAt(PAUSE));
        assertTrue(draft.pauseActive(rejectedAt, PAUSE));
        assertTrue(draft.pauseActive(rejectedAt.plus(PAUSE).minusMillis(1), PAUSE));
        assertFalse(draft.pauseActive(rejectedAt.plus(PAUSE), PAUSE), "ровно через 24 часа подать можно: «не раньше чем через 24 часа»");
        assertFalse(draft.pauseActive(rejectedAt.plus(PAUSE).plusSeconds(1), PAUSE));
        assertEquals(Optional.empty(), profile(SellerStatus.DRAFT, FULL, null).nextApplicationAt(PAUSE));
        assertFalse(profile(SellerStatus.DRAFT, FULL, null).pauseActive(T0, PAUSE));
    }

    @Test
    void submissionDuringThePauseIsRefusedWithTheDateWhenItBecomesPossible() {
        Instant rejectedAt = T0;
        SellerProfile draft = profile(SellerStatus.DRAFT, FULL, rejectedAt);
        Instant now = rejectedAt.plus(PAUSE).minusSeconds(1);
        ProblemException e = assertThrows(ProblemException.class, () -> draft.submit(1, now, PAUSE, REVIEW));
        assertEquals(ProblemType.APPLICATION_PAUSE_ACTIVE, e.type());
        assertEquals("2026-10-04T12:00:00.000Z", e.extensions().get("retryAfterAt"));
        assertEquals("1", e.headers().get("Retry-After"));
    }

    @Test
    void retryAfterIsRoundedUpToWholeSeconds() {
        SellerProfile draft = profile(SellerStatus.DRAFT, FULL, T0);
        ProblemException e = assertThrows(ProblemException.class, () -> draft.submit(1, T0.plus(PAUSE).minusMillis(1500), PAUSE, REVIEW));
        assertEquals("2", e.headers().get("Retry-After"));
        ProblemException early = assertThrows(ProblemException.class, () -> draft.submit(1, T0, PAUSE, REVIEW));
        assertEquals("86400", early.headers().get("Retry-After"));
    }

    @Test
    void submissionAfterThePauseIsAccepted() {
        SellerProfile draft = profile(SellerStatus.DRAFT, FULL, T0);
        assertEquals(SellerStatus.ON_REVIEW, draft.submit(1, T0.plus(PAUSE), PAUSE, REVIEW).status());
        assertEquals(SellerStatus.ON_REVIEW, draft.submit(1, T0.plus(PAUSE).plusSeconds(1), PAUSE, REVIEW).status());
    }

    @Test
    void rejectedProfileCannotBeSubmittedWithoutEditingFirst() {
        SellerProfile rejected = profile(SellerStatus.REJECTED, FULL, T0);
        ProblemException early = assertThrows(ProblemException.class, () -> rejected.submit(1, T0.plusSeconds(60), PAUSE, REVIEW));
        assertEquals(ProblemType.APPLICATION_PAUSE_ACTIVE, early.type(), "пока пауза идёт, заявитель видит, когда можно подать");
        ProblemException late = assertThrows(ProblemException.class, () -> rejected.submit(1, T0.plus(PAUSE), PAUSE, REVIEW));
        assertEquals(ProblemType.STATE_CONFLICT, late.type(), "пауза прошла, но из отказа в очередь только через черновик (T6)");
        assertEquals("rejected", late.extensions().get("currentStatus"));
    }

    @Test
    void doubleCheckOfThePauseOnTheFullPathRejectEditSubmit() {
        SellerProfile rejected = profile(SellerStatus.REJECTED, FULL, T0);
        SellerProfile reopened = rejected.replaceForm(FULL, T0.plusSeconds(30));
        assertThrows(ProblemException.class, () -> reopened.submit(1, T0.plus(PAUSE).minusSeconds(1), PAUSE, REVIEW));
        SellerProfile submitted = reopened.submit(1, T0.plus(PAUSE), PAUSE, REVIEW);
        assertEquals(SellerStatus.ON_REVIEW, submitted.status());
        assertEquals(T0, submitted.lastRejectedAt(), "дата отказа хранится: она нужна при следующем отказе и для истории");
    }

    @Test
    void returnedApplicationIsNotHeldByAnOldRejection() {
        SellerProfile returned = new SellerProfile(ID, USER, SellerStatus.RETURNED, FULL, "Комментарий", T0.minusSeconds(60), null, null, null, 3,
                T0.minusSeconds(100_000), T0);
        assertEquals(SellerStatus.ON_REVIEW, returned.submit(1, T0, PAUSE, REVIEW).status());
    }

    // ---------------------------------------------------------------- что видит заявитель

    @Test
    void rejectionDateIsShownAfterRejectionAndWhileFixingButNotLater() {
        Instant at = T0.minusSeconds(100);
        assertEquals(Optional.of(at), profile(SellerStatus.REJECTED, FULL, at).shownRejectedAt());
        assertEquals(Optional.of(at), profile(SellerStatus.DRAFT, FULL, at).shownRejectedAt());
        assertEquals(Optional.empty(), profile(SellerStatus.DRAFT, FULL, null).shownRejectedAt());
        assertEquals(Optional.empty(), profile(SellerStatus.ON_REVIEW, FULL, at).shownRejectedAt());
        assertEquals(Optional.empty(), profile(SellerStatus.APPROVED, FULL, at).shownRejectedAt());
        assertEquals(Optional.empty(), profile(SellerStatus.RETURNED, FULL, at).shownRejectedAt());
    }

    @Test
    void overdueMeansOnReviewPastTheDeadlineAndNotMarkedYet() {
        SellerProfile onReview = new SellerProfile(ID, USER, SellerStatus.ON_REVIEW, FULL, null, null, T0, T0.plus(REVIEW), null, 4, T0, T0);
        assertFalse(onReview.overdue(T0.plus(REVIEW).minusMillis(1)));
        assertTrue(onReview.overdue(T0.plus(REVIEW)));
        assertTrue(onReview.overdue(T0.plus(REVIEW).plusSeconds(1)));
        SellerProfile marked = new SellerProfile(ID, USER, SellerStatus.ON_REVIEW, FULL, null, null, T0, T0.plus(REVIEW), T0.plus(REVIEW), 4, T0, T0);
        assertFalse(marked.overdue(T0.plus(REVIEW).plusSeconds(60)), "пометка ставится один раз");
        assertFalse(profile(SellerStatus.DRAFT, FULL, null).overdue(T0.plus(Duration.ofDays(30))));
    }

    // ---------------------------------------------------------------- решения модератора (T3, T4, T5)

    private static SellerProfile onReview() {
        return new SellerProfile(ID, USER, SellerStatus.ON_REVIEW, FULL, null, T0.minus(Duration.ofDays(9)), T0.minusSeconds(7200),
                T0.minusSeconds(7200).plus(REVIEW), null, 4, T0.minusSeconds(86_400), T0.minusSeconds(7200));
    }

    @Test
    void approveMovesToApprovedAndKeepsTheHistoryOfTheReview() {
        SellerProfile before = onReview();
        SellerProfile approved = before.approve(T0);
        assertEquals(SellerStatus.APPROVED, approved.status());
        assertEquals(5, approved.version());
        assertEquals(T0, approved.updatedAt());
        assertNull(approved.decisionReason());
        assertEquals(before.lastRejectedAt(), approved.lastRejectedAt(), "прежний отказ остаётся в истории");
        assertEquals(before.submittedAt(), approved.submittedAt());
        assertEquals(before.reviewDeadlineAt(), approved.reviewDeadlineAt());
        assertEquals(before.form(), approved.form());
        assertEquals(before.createdAt(), approved.createdAt());
        assertTrue(SellerStatus.ON_REVIEW.canMoveTo(approved.status()));
    }

    @Test
    void rejectRecordsTheReasonAndStartsThePause() {
        SellerProfile rejected = onReview().reject("Документ не читается", T0);
        assertEquals(SellerStatus.REJECTED, rejected.status());
        assertEquals("Документ не читается", rejected.decisionReason());
        assertEquals(T0, rejected.lastRejectedAt());
        assertEquals(5, rejected.version());
        assertEquals(Optional.of(T0.plus(PAUSE)), rejected.nextApplicationAt(PAUSE));
        assertTrue(rejected.pauseActive(T0.plus(PAUSE).minusMillis(1), PAUSE));
        assertFalse(rejected.pauseActive(T0.plus(PAUSE), PAUSE));
        assertTrue(SellerStatus.ON_REVIEW.canMoveTo(rejected.status()));
    }

    @Test
    void returnRecordsTheCommentAndDoesNotStartAPause() {
        SellerProfile returned = onReview().returnForRework("Приложите свидетельство", T0);
        assertEquals(SellerStatus.RETURNED, returned.status());
        assertEquals("Приложите свидетельство", returned.decisionReason());
        assertEquals(onReview().lastRejectedAt(), returned.lastRejectedAt(), "возврат дату отказа не меняет");
        assertEquals(5, returned.version());
        assertTrue(SellerStatus.ON_REVIEW.canMoveTo(returned.status()));
        SellerProfile neverRejected = new SellerProfile(ID, USER, SellerStatus.ON_REVIEW, FULL, null, null, T0, T0.plus(REVIEW), null, 2, T0, T0);
        assertEquals(Optional.empty(), neverRejected.returnForRework("Дополните", T0).nextApplicationAt(PAUSE));
    }

    @ParameterizedTest
    @EnumSource(value = SellerStatus.class, names = {"DRAFT", "RETURNED", "APPROVED", "REJECTED", "BLOCKED"})
    void decisionsAreOnlyPossibleForAnApplicationOnReview(SellerStatus status) {
        SellerProfile profile = profile(status, FULL, T0.minusSeconds(100));
        for (java.util.function.Supplier<SellerProfile> decision : List.<java.util.function.Supplier<SellerProfile>>of(
                () -> profile.approve(T0), () -> profile.reject("Причина", T0), () -> profile.returnForRework("Комментарий", T0))) {
            ProblemException e = assertThrows(ProblemException.class, decision::get);
            assertEquals(ProblemType.STATE_CONFLICT, e.type());
            assertEquals(status.code(), e.extensions().get("currentStatus"));
        }
    }

    @Test
    void theApplicationIsVisibleToTheModeratorOnceSubmittedAndNeverAsADraft() {
        assertFalse(profile(SellerStatus.DRAFT, FULL, null).visibleToModerator(), "черновик не отправляли");
        SellerProfile reopened = new SellerProfile(ID, USER, SellerStatus.DRAFT, FULL, "Причина", T0, T0.minusSeconds(900), T0, null, 6, T0, T0);
        assertFalse(reopened.visibleToModerator(), "после отказа и правки заявка снова черновик заявителя");
        assertTrue(onReview().visibleToModerator());
        assertTrue(onReview().approve(T0).visibleToModerator());
        assertTrue(onReview().reject("Причина", T0).visibleToModerator());
        assertTrue(onReview().returnForRework("Комментарий", T0).visibleToModerator());
    }

    @Test
    void pastDeadlineIgnoresTheMarkOfTheJobAndIsFalseOutsideTheQueue() {
        SellerProfile queued = onReview();
        Instant deadline = queued.reviewDeadlineAt();
        assertFalse(queued.pastDeadline(deadline.minusMillis(1)));
        assertTrue(queued.pastDeadline(deadline));
        SellerProfile marked = new SellerProfile(ID, USER, SellerStatus.ON_REVIEW, FULL, null, null, T0, T0.plus(REVIEW), T0.plus(REVIEW), 4, T0, T0);
        assertTrue(marked.pastDeadline(T0.plus(REVIEW).plusSeconds(60)), "в очереди просроченная заявка видна и после пометки");
        assertFalse(onReview().approve(T0).pastDeadline(deadline.plus(Duration.ofDays(30))));
    }
}
