package dgm.catalog.sellers.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Модель SM-05 (docs/03-processes/SM-05-seller-profile.md): каждая пара статусов проверяется отдельно, разрешённые переходы это
 * ровно T2 … T7, и список совпадает с триггером в миграции базы.
 */
@Tag("TC-205")
@Tag("TC-211")
class SellerStatusTest {

    private static final Set<String> ALLOWED = Set.of("draft>on_review", "on_review>approved", "on_review>rejected", "on_review>returned",
            "rejected>draft", "returned>on_review");

    @ParameterizedTest
    @EnumSource(SellerStatus.class)
    void fromEveryStatusOnlyTheModelledTransitionsAreAllowed(SellerStatus from) {
        for (SellerStatus to : SellerStatus.values()) {
            boolean expected = ALLOWED.contains(from.code() + ">" + to.code());
            assertEquals(expected, from.canMoveTo(to), from + " → " + to);
            assertEquals(expected, from.transitionTo(to).isPresent(), from + " → " + to);
        }
    }

    @Test
    void transitionNumbersFollowTheModel() {
        assertEquals(SellerTransition.T2_SUBMIT, SellerStatus.DRAFT.transitionTo(SellerStatus.ON_REVIEW).orElseThrow());
        assertEquals(SellerTransition.T3_APPROVE, SellerStatus.ON_REVIEW.transitionTo(SellerStatus.APPROVED).orElseThrow());
        assertEquals(SellerTransition.T4_REJECT, SellerStatus.ON_REVIEW.transitionTo(SellerStatus.REJECTED).orElseThrow());
        assertEquals(SellerTransition.T5_RETURN, SellerStatus.ON_REVIEW.transitionTo(SellerStatus.RETURNED).orElseThrow());
        assertEquals(SellerTransition.T6_REOPEN, SellerStatus.REJECTED.transitionTo(SellerStatus.DRAFT).orElseThrow());
        assertEquals(SellerTransition.T7_RESUBMIT, SellerStatus.RETURNED.transitionTo(SellerStatus.ON_REVIEW).orElseThrow());
        assertEquals(6, SellerTransition.values().length);
    }

    @Test
    void approvedAndBlockedHaveNoWayBackInRelease1() {
        for (SellerStatus to : SellerStatus.values()) {
            assertFalse(SellerStatus.APPROVED.canMoveTo(to), "из «одобрен» в R1 выхода нет: " + to);
            assertFalse(SellerStatus.BLOCKED.canMoveTo(to), "блокировка это R2: " + to);
            assertFalse(to.canMoveTo(to), "переход в тот же статус не переход: " + to);
        }
    }

    @ParameterizedTest
    @EnumSource(SellerStatus.class)
    void applicantMayEditOnlyDraftReturnedAndRejected(SellerStatus status) {
        Set<SellerStatus> editable = EnumSet.of(SellerStatus.DRAFT, SellerStatus.RETURNED, SellerStatus.REJECTED);
        assertEquals(editable.contains(status), status.applicantMayEdit(), status.toString());
    }

    @Test
    void codesAreTheOnesStoredInTheDatabase() {
        for (SellerStatus status : SellerStatus.values()) {
            assertEquals(status, SellerStatus.fromCode(status.code()));
        }
        assertEquals(Set.of("draft", "on_review", "returned", "approved", "rejected", "blocked"),
                new TreeSet<>(java.util.Arrays.stream(SellerStatus.values()).map(SellerStatus::code).toList()));
    }

    /** Код и триггер базы описывают одну и ту же модель: расхождение найдёт этот тест, а не ночная ошибка в базе. */
    @Test
    void transitionsMatchTheTriggerInTheMigration() throws IOException {
        String sql;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("db/migration/seller_onboarding/V2__seller_onboarding.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = sql.indexOf("trg_seller_profile_status_transition");
        int end = sql.indexOf(");", start);
        Matcher matcher = Pattern.compile("'([a-z_]+>[a-z_]+)'").matcher(sql.substring(start, end));
        Set<String> inDatabase = new TreeSet<>();
        while (matcher.find()) {
            inDatabase.add(matcher.group(1));
        }
        assertEquals(new TreeSet<>(ALLOWED), inDatabase);
        assertTrue(sql.contains("initial:draft"));
    }
}
