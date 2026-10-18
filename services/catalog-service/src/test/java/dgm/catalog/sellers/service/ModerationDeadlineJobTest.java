package dgm.catalog.sellers.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.FakeTransaction;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Контроль срока рассмотрения: пометка просрочки один раз, статус не меняется, решения по таймеру нет (BG-05). */
@Tag("TC-209")
class ModerationDeadlineJobTest {

    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");
    private static final UUID OTHER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a3");
    private static final UUID THIRD = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a4");

    private ManualClock clock;
    private FakeSellers sellers;
    private MeterRegistry meters;
    private ModerationDeadlineJob job;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        sellers = new FakeSellers();
        meters = new SimpleMeterRegistry();
        job = new ModerationDeadlineJob(sellers, new FakeTransaction(), clock, meters);
    }

    private static SellerProfile onReview(UUID user, Instant submittedAt, Duration period) {
        return new SellerProfile(UUID.nameUUIDFromBytes(user.toString().getBytes()), user, SellerStatus.ON_REVIEW, FakeSellers.completeForm(), null, null,
                submittedAt, submittedAt.plus(period), null, 2, submittedAt, submittedAt);
    }

    private long counter() {
        return Math.round(meters.counter("moderation_overdue_total", "subject", "seller_application").count());
    }

    @Test
    void nothingIsMarkedBeforeTheDeadline() {
        sellers.put(onReview(FakeSellers.USER, T0, Duration.ofDays(3)));
        clock.advance(Duration.ofDays(3).minusMillis(1));
        assertEquals(0, job.runPass());
        assertNull(sellers.stored(FakeSellers.USER).overdueMarkedAt());
        assertEquals(0, counter());
    }

    @Test
    void overdueApplicationIsMarkedOnceAtTheMomentOfThePass() {
        sellers.put(onReview(FakeSellers.USER, T0, Duration.ofDays(3)));
        clock.advance(Duration.ofDays(3).plusMinutes(2));
        assertEquals(1, job.runPass());
        SellerProfile marked = sellers.stored(FakeSellers.USER);
        assertEquals(clock.instant(), marked.overdueMarkedAt());
        assertEquals(SellerStatus.ON_REVIEW, marked.status(), "просрочка не принимает решения по заявке");
        assertEquals(2, marked.version(), "служебная пометка версию анкеты не меняет");
        assertEquals(1, counter());

        clock.advance(Duration.ofMinutes(5));
        assertEquals(0, job.runPass(), "пометка ставится один раз");
        assertEquals(T0.plus(Duration.ofDays(3)).plus(Duration.ofMinutes(2)), sellers.stored(FakeSellers.USER).overdueMarkedAt());
        assertEquals(1, counter());
    }

    @Test
    void deadlineMomentItselfCountsAsOverdue() {
        sellers.put(onReview(FakeSellers.USER, T0, Duration.ofSeconds(10)));
        clock.advance(Duration.ofSeconds(10));
        assertEquals(1, job.runPass());
    }

    @Test
    void onlyApplicationsOnReviewAreChecked() {
        sellers.put(onReview(FakeSellers.USER, T0, Duration.ofDays(3)));
        sellers.put(FakeSellers.profile(OTHER, SellerStatus.DRAFT, FakeSellers.completeForm(), T0));
        sellers.put(FakeSellers.profile(THIRD, SellerStatus.RETURNED, FakeSellers.completeForm(), T0));
        clock.advance(Duration.ofDays(30));
        assertEquals(1, job.runPass());
        assertNotNull(sellers.stored(FakeSellers.USER).overdueMarkedAt());
        assertNull(sellers.stored(OTHER).overdueMarkedAt());
        assertNull(sellers.stored(THIRD).overdueMarkedAt());
    }

    @Test
    void severalOverdueApplicationsAreMarkedTogether() {
        sellers.put(onReview(FakeSellers.USER, T0, Duration.ofDays(3)));
        sellers.put(onReview(OTHER, T0.plusSeconds(60), Duration.ofDays(3)));
        sellers.put(onReview(THIRD, T0.plus(Duration.ofDays(2)), Duration.ofDays(3)));
        clock.advance(Duration.ofDays(3).plusMinutes(5));
        assertEquals(2, job.runPass());
        assertEquals(2, counter());
        assertNull(sellers.stored(THIRD).overdueMarkedAt());
        // Срок третьей заявки наступает через 5 суток от T0: к этому моменту прошло 3 суток и 5 минут, ещё 2 суток закрывают срок
        clock.advance(Duration.ofDays(2));
        assertEquals(1, job.runPass());
        assertEquals(3, counter());
    }

    @Test
    void resubmittedApplicationCanBeMarkedAgainForItsNewDeadline() {
        SellerProfile first = onReview(FakeSellers.USER, T0, Duration.ofSeconds(10));
        sellers.put(first);
        clock.advance(Duration.ofSeconds(11));
        assertEquals(1, job.runPass());
        // Возврат на доработку и повторная подача сбрасывают пометку (SellerProfile.submit): новый срок, новая пометка
        SellerProfile resubmitted = new SellerProfile(first.id(), first.userId(), SellerStatus.ON_REVIEW, first.form(), null, null,
                clock.instant(), clock.instant().plusSeconds(10), null, 4, first.createdAt(), clock.instant());
        sellers.put(resubmitted);
        assertEquals(0, job.runPass());
        clock.advance(Duration.ofSeconds(10));
        assertEquals(1, job.runPass());
        assertEquals(2, counter());
    }
}
