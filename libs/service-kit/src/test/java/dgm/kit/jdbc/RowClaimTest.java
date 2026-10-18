package dgm.kit.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Помощник аренды без базы: текст запросов, проверка имён и выражений, границы параметров. Поведение на двух потребителях проверяет LeaseIT. */
class RowClaimTest {

    private static RowClaim.Builder dispatch() {
        return RowClaim.on("delivery.delivery").where("status = 'queued'").dueColumn("next_attempt_at");
    }

    @Test
    void claimTakesRowsWithSkipLockedAndShiftsTheLeaseColumn() {
        RowClaim claim = dispatch().alsoSet("attempt_started_at = now()").returning("id, order_id, attempt_no").build();
        String sql = claim.claimSql();
        assertEquals("with picked as materialized (select id as claim_id from delivery.delivery where (status = 'queued') "
                + "and next_attempt_at <= ? order by next_attempt_at, id limit ? for update skip locked) "
                + "update delivery.delivery set next_attempt_at = ?, attempt_started_at = now() from picked "
                + "where delivery.delivery.id = picked.claim_id returning id, order_id, attempt_no", sql);
    }

    @Test
    void defaultsReturnAllColumnsAndOrderByDueThenId() {
        String sql = dispatch().build().claimSql();
        assertTrue(sql.endsWith("returning delivery.delivery.*"), sql);
        assertTrue(sql.contains("order by next_attempt_at, id limit ?"), sql);
    }

    @Test
    void separateLeaseColumnIdColumnAndOrderAreUsed() {
        String sql = RowClaim.on("refund_attempt").where("status = 'pending'").dueColumn("due_at").leaseColumn("leased_until")
                .idColumn("attempt_id").orderBy("due_at desc, attempt_id").build().claimSql();
        assertTrue(sql.contains("select attempt_id as claim_id from refund_attempt"), sql);
        assertTrue(sql.contains("order by due_at desc, attempt_id limit ?"), sql);
        assertTrue(sql.contains("set leased_until = ? from picked where refund_attempt.attempt_id = picked.claim_id"), sql);
    }

    @Test
    void lockIsPlainSelectForUpdateSkipLocked() {
        String sql = dispatch().returning("id, status").build().lockSql();
        assertEquals("select id, status from delivery.delivery where (status = 'queued') and next_attempt_at <= ? "
                + "order by next_attempt_at, id limit ? for update skip locked", sql);
    }

    @Test
    void queueWithoutConditionOrDueColumnCannotBeBuilt() {
        assertThrows(IllegalStateException.class, () -> RowClaim.on("t").dueColumn("due_at").build());
        assertThrows(IllegalStateException.class, () -> RowClaim.on("t").where("status = 'x'").build());
    }

    @Test
    void tableAndColumnNamesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> RowClaim.on("t; drop table x"));
        assertThrows(IllegalArgumentException.class, () -> RowClaim.on("a.b.c"));
        assertThrows(IllegalArgumentException.class, () -> RowClaim.on("Orders"));
        assertThrows(IllegalArgumentException.class, () -> RowClaim.on(null));
        assertThrows(IllegalArgumentException.class, () -> dispatch().dueColumn("due at"));
        assertThrows(IllegalArgumentException.class, () -> dispatch().idColumn("id) or (1=1"));
        assertThrows(IllegalArgumentException.class, () -> dispatch().leaseColumn("x, y"));
    }

    @Test
    void expressionsCannotCarryStatementsCommentsOrParameters() {
        assertThrows(IllegalArgumentException.class, () -> dispatch().where("status = 'queued'; delete from delivery.delivery"));
        assertThrows(IllegalArgumentException.class, () -> dispatch().where("status = 'queued' -- хвост"));
        assertThrows(IllegalArgumentException.class, () -> dispatch().where("status = ?"));
        assertThrows(IllegalArgumentException.class, () -> dispatch().orderBy("id /* x */"));
        assertThrows(IllegalArgumentException.class, () -> dispatch().alsoSet(" "));
        assertThrows(IllegalArgumentException.class, () -> dispatch().returning(null));
    }

    @Test
    void batchAndLeaseBoundsAreChecked() {
        RowClaim claim = dispatch().build();
        JdbcTemplate jdbc = new JdbcTemplate();
        Instant now = Instant.parse("2026-10-18T10:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> claim.claim(jdbc, now, Duration.ofMinutes(2), 0, (rs, i) -> rs.getString(1)));
        assertThrows(IllegalArgumentException.class,
                () -> claim.claim(jdbc, now, Duration.ofMinutes(2), RowClaim.MAX_BATCH + 1, (rs, i) -> rs.getString(1)));
        assertThrows(IllegalArgumentException.class, () -> claim.claim(jdbc, now, Duration.ZERO, 5, (rs, i) -> rs.getString(1)));
        assertThrows(IllegalArgumentException.class, () -> claim.claim(jdbc, now, Duration.ofSeconds(-1), 5, (rs, i) -> rs.getString(1)));
    }

    @Test
    void lockWithoutTransactionIsRefused() {
        RowClaim claim = dispatch().build();
        assertThrows(IllegalStateException.class,
                () -> claim.lock(new JdbcTemplate(), Instant.parse("2026-10-18T10:00:00Z"), 5, (rs, i) -> rs.getString(1)));
        assertEquals("delivery.delivery", claim.table());
    }
}
