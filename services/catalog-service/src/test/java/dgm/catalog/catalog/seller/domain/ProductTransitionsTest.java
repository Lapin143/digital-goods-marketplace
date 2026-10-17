package dgm.catalog.catalog.seller.domain;

import static dgm.catalog.catalog.seller.Products.REVIEW;
import static dgm.catalog.catalog.seller.Products.SELLER;
import static dgm.catalog.catalog.seller.Products.T0;
import static dgm.catalog.catalog.seller.Products.form;
import static dgm.catalog.catalog.seller.Products.id;
import static dgm.catalog.catalog.seller.Products.in;
import static dgm.catalog.catalog.seller.Products.sellerBlocked;
import static dgm.catalog.catalog.seller.Products.withDescription;
import static dgm.catalog.catalog.seller.Products.withIssuance;
import static dgm.catalog.catalog.seller.Products.withPlatform;
import static dgm.catalog.catalog.seller.Products.withPrice;
import static dgm.catalog.catalog.seller.Products.withRegions;
import static dgm.catalog.catalog.seller.Products.withTitle;
import static dgm.catalog.catalog.seller.Products.withType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.catalog.domain.IssuanceMethod;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductForm;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.catalog.catalog.domain.ProductType;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Переходы SM-04, которые делает продавец: T1 (создание), T2, T5, T11 (отправка из трёх статусов), T6 и T7 (правка опубликованного),
 * T12 (архив), T13 (восстановление), а также все запрещённые сочетания «статус × действие». Решения модератора T3, T4, T8 это шаг 13.
 */
class ProductTransitionsTest {

    private static final Instant LATER = T0.plusSeconds(3600);

    private static ProblemException conflict(Runnable action) {
        ProblemException e = assertThrows(ProblemException.class, action::run);
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        return e;
    }

    // ------------------------------------------------------------------ T1

    @Test
    void t1CreatesADraftOfVersionOneWithoutModerationData() {
        Product p = Product.create(id(1), SELLER, form(), T0);
        assertEquals(ProductStatus.DRAFT, p.status());
        assertEquals(1, p.version());
        assertEquals(T0, p.createdAt());
        assertEquals(T0, p.updatedAt());
        assertNull(p.submittedAt());
        assertNull(p.reviewDeadlineAt());
        assertNull(p.publishedAt());
        assertNull(p.rejectionReason());
        assertNull(p.blockReason());
    }

    @Test
    void createRefusesAFormWithViolations() {
        assertThrows(IllegalArgumentException.class, () -> Product.create(id(1), SELLER, withTitle(form(), ""), T0));
        assertThrows(IllegalArgumentException.class, () -> Product.create(id(1), SELLER, withIssuance(form(), IssuanceMethod.SELLER_API), T0));
    }

    // ------------------------------------------------------------------ T2, T5, T11: отправка на модерацию

    static Stream<Arguments> submittable() {
        return Stream.of(
                Arguments.of("T2 черновик", in(ProductStatus.DRAFT, 3)),
                Arguments.of("T5 отклонён", in(ProductStatus.REJECTED, 3)),
                Arguments.of("T11 заблокирован модератором", in(ProductStatus.BLOCKED, 3)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("submittable")
    void submitMovesToModerationWithAFreshDeadlineAndClearsTheOldDecision(String name, Product before) {
        Product after = before.submit(LATER, REVIEW);
        assertEquals(ProductStatus.ON_MODERATION, after.status());
        assertEquals(4, after.version());
        assertEquals(LATER, after.submittedAt());
        assertEquals(LATER.plus(REVIEW), after.reviewDeadlineAt());
        assertEquals(LATER, after.updatedAt());
        assertNull(after.rejectionReason());
        assertNull(after.blockReason());
        assertNull(after.blockNote());
        assertNull(after.statusBeforeBlock());
        assertNull(after.overdueMarkedAt());
        assertEquals(before.form(), after.form());
    }

    static Stream<Arguments> notSubmittable() {
        return Stream.of(
                Arguments.of("на модерации: срок не сбрасывается", in(ProductStatus.ON_MODERATION, 2)),
                Arguments.of("опубликован", in(ProductStatus.PUBLISHED, 2)),
                Arguments.of("архив", in(ProductStatus.ARCHIVED, 2)),
                Arguments.of("заблокирован вместе с продавцом", sellerBlocked(2)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notSubmittable")
    void submitIsRefusedElsewhere(String name, Product before) {
        ProblemException e = conflict(() -> before.submit(LATER, REVIEW));
        assertEquals(before.status().code(), e.extensions().get("currentStatus"));
    }

    // ------------------------------------------------------------------ T12: архив

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = {"DRAFT", "ON_MODERATION", "PUBLISHED", "REJECTED"})
    void t12ArchivesFromFourStatusesAndLeavesTheQueue(ProductStatus from) {
        Product after = in(from, 5).archive(LATER);
        assertEquals(ProductStatus.ARCHIVED, after.status());
        assertEquals(6, after.version());
        assertNull(after.submittedAt());
        assertNull(after.reviewDeadlineAt());
        assertNull(after.rejectionReason());
        assertEquals(LATER, after.updatedAt());
    }

    @Test
    void archivedProductKeepsTheLastPublicationTime() {
        Product published = in(ProductStatus.PUBLISHED, 2);
        assertEquals(published.publishedAt(), published.archive(LATER).publishedAt());
    }

    @Test
    void archiveIsRefusedForBlockedAndArchived() {
        conflict(() -> in(ProductStatus.BLOCKED, 2).archive(LATER));
        conflict(() -> sellerBlocked(2).archive(LATER));
        conflict(() -> in(ProductStatus.ARCHIVED, 2).archive(LATER));
    }

    // ------------------------------------------------------------------ T13: восстановление

    @Test
    void t13RestoresAnArchivedProductToDraft() {
        Product after = in(ProductStatus.ARCHIVED, 7).restore(LATER);
        assertEquals(ProductStatus.DRAFT, after.status());
        assertEquals(8, after.version());
        assertNull(after.submittedAt());
    }

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = {"DRAFT", "ON_MODERATION", "PUBLISHED", "REJECTED", "BLOCKED"})
    void restoreIsRefusedOutsideTheArchive(ProductStatus from) {
        conflict(() -> in(from, 2).restore(LATER));
    }

    // ------------------------------------------------------------------ T6, T7 и правка в остальных статусах

    static Stream<Arguments> saveOnlyStatuses() {
        return Stream.of(
                Arguments.of("черновик", in(ProductStatus.DRAFT, 2)),
                Arguments.of("отклонён", in(ProductStatus.REJECTED, 2)),
                Arguments.of("заблокирован модератором", in(ProductStatus.BLOCKED, 2)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("saveOnlyStatuses")
    void editInDraftRejectedAndModeratorBlockedOnlySavesAndKeepsTheStatus(String name, Product before) {
        Product.Edited edited = before.replace(withTitle(form(), "Новое название"), LATER, REVIEW);
        assertEquals(before.status(), edited.product().status());
        assertEquals(List.of("title"), edited.changedFields());
        assertEquals(3, edited.product().version());
        assertEquals("Новое название", edited.product().form().title());
        assertEquals(before.rejectionReason(), edited.product().rejectionReason());
        assertEquals(before.blockNote(), edited.product().blockNote());
        assertEquals(before.submittedAt(), edited.product().submittedAt());
        assertEquals(LATER, edited.product().updatedAt());
    }

    static Stream<Arguments> t6Edits() {
        return Stream.<Function<ProductForm, ProductForm>>of(
                f -> withTitle(f, "Другое название"),
                f -> withDescription(f, "Другое описание"),
                f -> withType(f, ProductType.SOFTWARE_LICENSE)).map(change -> Arguments.of(change));
    }

    @ParameterizedTest
    @MethodSource("t6Edits")
    void t6EditOfTitleDescriptionOrTypeReturnsAPublishedProductToModeration(Function<ProductForm, ProductForm> change) {
        Product before = in(ProductStatus.PUBLISHED, 4);
        Product.Edited edited = before.replace(change.apply(before.form()), LATER, REVIEW);
        Product after = edited.product();
        assertEquals(ProductStatus.ON_MODERATION, after.status());
        assertEquals(5, after.version());
        assertEquals(LATER, after.submittedAt());
        assertEquals(LATER.plus(REVIEW), after.reviewDeadlineAt());
        assertEquals(before.publishedAt(), after.publishedAt());
        assertTrue(edited.changedFields().contains("status"));
        assertEquals("status", edited.changedFields().get(edited.changedFields().size() - 1));
    }

    static Stream<Arguments> t7Edits() {
        return Stream.<Function<ProductForm, ProductForm>>of(
                f -> withPrice(f, 99_900L),
                f -> withPlatform(f, "GOG"),
                f -> withRegions(f, List.of()),
                f -> withPrice(withPlatform(f, "Epic Games"), 1L)).map(change -> Arguments.of(change));
    }

    @ParameterizedTest
    @MethodSource("t7Edits")
    void t7EditOfPricePlatformOrRegionKeepsAPublishedProductPublished(Function<ProductForm, ProductForm> change) {
        Product before = in(ProductStatus.PUBLISHED, 4);
        Product.Edited edited = before.replace(change.apply(before.form()), LATER, REVIEW);
        assertTrue(edited.changed());
        assertEquals(ProductStatus.PUBLISHED, edited.product().status());
        assertEquals(5, edited.product().version());
        assertNull(edited.product().submittedAt());
        assertEquals(false, edited.changedFields().contains("status"));
    }

    @Test
    void editWithoutDifferencesChangesNothingAndKeepsTheVersion() {
        Product before = in(ProductStatus.PUBLISHED, 4);
        Product.Edited edited = before.replace(withRegions(before.form(), List.of("KZ", "RU")), LATER, REVIEW);
        assertSame(before, edited.product());
        assertEquals(false, edited.changed());
        assertEquals(4, edited.product().version());
    }

    @Test
    void mixedEditOfPriceAndTitleGoesToModerationAndNamesBothFieldsAndStatus() {
        Product before = in(ProductStatus.PUBLISHED, 1);
        Product.Edited edited = before.replace(withPrice(withTitle(before.form(), "Новое"), 1L), LATER, REVIEW);
        assertEquals(ProductStatus.ON_MODERATION, edited.product().status());
        assertEquals(List.of("title", "price", "status"), edited.changedFields());
    }

    @Test
    void editIsRefusedOnModerationInTheArchiveAndForSellerBlocked() {
        ProductForm other = withTitle(form(), "Другое");
        conflict(() -> in(ProductStatus.ON_MODERATION, 2).replace(other, LATER, REVIEW));
        conflict(() -> in(ProductStatus.ARCHIVED, 2).replace(other, LATER, REVIEW));
        conflict(() -> sellerBlocked(2).replace(other, LATER, REVIEW));
    }

    @Test
    void editRefusesAFormWithViolationsBeforeComparing() {
        assertThrows(IllegalArgumentException.class, () -> in(ProductStatus.DRAFT, 2).replace(withTitle(form(), ""), LATER, REVIEW));
    }

    // ------------------------------------------------------------------ вся матрица «статус × действие»

    enum Action { EDIT_PRICE, EDIT_TITLE, SUBMIT, ARCHIVE, RESTORE }

    private static ProductStatus apply(Product product, Action action) {
        return switch (action) {
            case EDIT_PRICE -> product.replace(withPrice(product.form(), 1L), LATER, REVIEW).product().status();
            case EDIT_TITLE -> product.replace(withTitle(product.form(), "Новое"), LATER, REVIEW).product().status();
            case SUBMIT -> product.submit(LATER, REVIEW).status();
            case ARCHIVE -> product.archive(LATER).status();
            case RESTORE -> product.restore(LATER).status();
        };
    }

    /** Статус продавца-действия: ожидаемый итоговый статус или {@code null}, если действие запрещено (409). */
    static Stream<Arguments> matrix() {
        ProductStatus d = ProductStatus.DRAFT;
        ProductStatus m = ProductStatus.ON_MODERATION;
        ProductStatus p = ProductStatus.PUBLISHED;
        ProductStatus r = ProductStatus.REJECTED;
        ProductStatus b = ProductStatus.BLOCKED;
        ProductStatus a = ProductStatus.ARCHIVED;
        return Stream.of(
                Arguments.of(d, Action.EDIT_PRICE, d), Arguments.of(d, Action.EDIT_TITLE, d), Arguments.of(d, Action.SUBMIT, m),
                Arguments.of(d, Action.ARCHIVE, a), Arguments.of(d, Action.RESTORE, null),
                Arguments.of(m, Action.EDIT_PRICE, null), Arguments.of(m, Action.EDIT_TITLE, null), Arguments.of(m, Action.SUBMIT, null),
                Arguments.of(m, Action.ARCHIVE, a), Arguments.of(m, Action.RESTORE, null),
                Arguments.of(p, Action.EDIT_PRICE, p), Arguments.of(p, Action.EDIT_TITLE, m), Arguments.of(p, Action.SUBMIT, null),
                Arguments.of(p, Action.ARCHIVE, a), Arguments.of(p, Action.RESTORE, null),
                Arguments.of(r, Action.EDIT_PRICE, r), Arguments.of(r, Action.EDIT_TITLE, r), Arguments.of(r, Action.SUBMIT, m),
                Arguments.of(r, Action.ARCHIVE, a), Arguments.of(r, Action.RESTORE, null),
                Arguments.of(b, Action.EDIT_PRICE, b), Arguments.of(b, Action.EDIT_TITLE, b), Arguments.of(b, Action.SUBMIT, m),
                Arguments.of(b, Action.ARCHIVE, null), Arguments.of(b, Action.RESTORE, null),
                Arguments.of(a, Action.EDIT_PRICE, null), Arguments.of(a, Action.EDIT_TITLE, null), Arguments.of(a, Action.SUBMIT, null),
                Arguments.of(a, Action.ARCHIVE, null), Arguments.of(a, Action.RESTORE, d));
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @MethodSource("matrix")
    void everyStatusAndActionPairGivesTheExpectedStatusOrAConflict(ProductStatus from, Action action, ProductStatus expected) {
        Product product = in(from, 3);
        if (expected == null) {
            conflict(() -> apply(product, action));
        } else {
            assertEquals(expected, apply(product, action));
        }
    }

    @Test
    void theMatrixCoversEverySellerActionInEveryStatus() {
        assertEquals(ProductStatus.values().length * Action.values().length, matrix().count());
    }
}
