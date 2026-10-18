package dgm.catalog.catalog.moderation.domain;

import static dgm.catalog.catalog.seller.Products.REVIEW;
import static dgm.catalog.catalog.seller.Products.T0;
import static dgm.catalog.catalog.seller.Products.in;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.catalog.domain.BlockReason;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** SM-04: решения модератора T3, T4, T8 и допустимые пары статусов. */
@Tag("TC-311")
@Tag("TC-312")
@Tag("TC-313")
@Tag("TC-314")
class ProductDecisionsTest {

    private static final Instant NOW = T0.plus(Duration.ofHours(5));

    private static ProblemException refused(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    // ---------------------------------------------------------------- T3

    @Test
    void approvePublishesTheProductAndClosesTheReviewPeriod() {
        Product queued = in(ProductStatus.ON_MODERATION, 4);
        Product published = queued.approve(NOW);
        assertEquals(ProductStatus.PUBLISHED, published.status());
        assertEquals(5, published.version());
        assertEquals(NOW, published.publishedAt(), "порядок витрины «новые выше» идёт от одобрения");
        assertEquals(NOW, published.updatedAt());
        assertNull(published.submittedAt());
        assertNull(published.reviewDeadlineAt());
        assertNull(published.overdueMarkedAt());
        assertNull(published.rejectionReason());
        assertEquals(queued.form(), published.form());
    }

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = "ON_MODERATION", mode = EnumSource.Mode.EXCLUDE)
    void approveFromAnyOtherStatusIsAStateConflictWithTheCurrentStatus(ProductStatus status) {
        ProblemException e = refused(() -> in(status, 2).approve(NOW));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals(status.code(), e.extensions().get("currentStatus"));
    }

    // ---------------------------------------------------------------- T4

    @Test
    void rejectKeepsTheReasonForTheSellerAndLeavesTheQueue() {
        Product rejected = in(ProductStatus.ON_MODERATION, 4).reject("Описание не соответствует товару", NOW);
        assertEquals(ProductStatus.REJECTED, rejected.status());
        assertEquals("Описание не соответствует товару", rejected.rejectionReason());
        assertEquals(5, rejected.version());
        assertNull(rejected.submittedAt());
        assertNull(rejected.reviewDeadlineAt());
        assertNull(rejected.blockReason());
    }

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = "ON_MODERATION", mode = EnumSource.Mode.EXCLUDE)
    void rejectFromAnyOtherStatusIsAStateConflict(ProductStatus status) {
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> in(status, 2).reject("Причина", NOW)).type());
    }

    @Test
    void aRejectedProductComesBackThroughTheSellersResubmissionAndCanBeApproved() {
        Product rejected = in(ProductStatus.ON_MODERATION, 2).reject("Причина", NOW);
        Product again = rejected.submit(NOW.plusSeconds(60), REVIEW);
        assertNull(again.rejectionReason(), "причина прошлого отклонения снимается при новой подаче");
        Product published = again.approve(NOW.plusSeconds(120));
        assertEquals(ProductStatus.PUBLISHED, published.status());
        assertNull(published.rejectionReason());
    }

    // ---------------------------------------------------------------- T8

    @Test
    void blockTakesAPublishedProductOffTheShelfWithTheModeratorsReason() {
        Product blocked = in(ProductStatus.PUBLISHED, 6).block("Жалоба правообладателя", NOW);
        assertEquals(ProductStatus.BLOCKED, blocked.status());
        assertEquals(ProductStatus.PUBLISHED, blocked.statusBeforeBlock(), "INV-40");
        assertEquals(BlockReason.MODERATOR_DECISION, blocked.blockReason());
        assertEquals("Жалоба правообладателя", blocked.blockNote());
        assertEquals(7, blocked.version());
        assertTrue(blocked.moderatorBlocked());
        assertEquals(in(ProductStatus.PUBLISHED, 6).publishedAt(), blocked.publishedAt());
    }

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = "PUBLISHED", mode = EnumSource.Mode.EXCLUDE)
    void blockFromAnyOtherStatusIsAStateConflict(ProductStatus status) {
        ProblemException e = refused(() -> in(status, 2).block("Причина", NOW));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals(status.code(), e.extensions().get("currentStatus"));
    }

    @Test
    void aBlockedProductGoesBackThroughModerationAfterTheSellersFix() {
        Product blocked = in(ProductStatus.PUBLISHED, 2).block("Чужой товарный знак", NOW);
        Product resubmitted = blocked.submit(NOW.plusSeconds(30), REVIEW);
        assertEquals(ProductStatus.ON_MODERATION, resubmitted.status());
        assertNull(resubmitted.blockNote(), "T11 снимает поля блокировки");
        assertNull(resubmitted.blockReason());
        assertNull(resubmitted.statusBeforeBlock());
        assertEquals(ProductStatus.PUBLISHED, resubmitted.approve(NOW.plusSeconds(90)).status());
    }

    // ---------------------------------------------------------------- видимость

    @Test
    void aDraftDoesNotExistForTheModeratorEverythingElseIsVisible() {
        Set<ProductStatus> visible = EnumSet.noneOf(ProductStatus.class);
        for (ProductStatus status : ProductStatus.values()) {
            if (in(status, 1).visibleToModerator()) {
                visible.add(status);
            }
        }
        assertEquals(EnumSet.complementOf(EnumSet.of(ProductStatus.DRAFT)), visible);
        assertFalse(in(ProductStatus.DRAFT, 1).visibleToModerator());
    }
}
