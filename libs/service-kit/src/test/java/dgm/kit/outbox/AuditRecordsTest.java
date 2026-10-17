package dgm.kit.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.trace.TraceContext;
import dgm.kit.trace.TraceContexts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Данные события {@code audit.recorded}: набор полей по схеме AsyncAPI, проверки значений и сквозной идентификатор из контекста. */
class AuditRecordsTest {

    private static final UUID ACTOR = UUID.fromString("5d3b1f79-8c2e-4a60-b4d8-6e0a2c4f1b37");
    private static final Instant AT = Instant.parse("2026-10-03T12:30:00.250Z");
    private static final Set<String> SCHEMA_FIELDS = Set.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes",
            "actorIp", "actorIpHashed", "correlationId");

    private static Map<String, Object> record(String role, String action, String ip, List<Map<String, Object>> changes) {
        return AuditRecords.staffAction(AT, ACTOR, role, ip, action, "seller", "0199e0a0-0000-7000-8000-000000000101", changes);
    }

    private static List<Map<String, Object>> oneChange() {
        return List.of(AuditRecords.change("status", "on_review", "approved"));
    }

    @Test
    void recordCarriesExactlyTheFieldsOfTheSchema() {
        Map<String, Object> data = record("moderator", "seller.approved", "203.0.113.7", oneChange());
        assertEquals(SCHEMA_FIELDS, data.keySet());
        assertEquals("2026-10-03T12:30:00.250Z", data.get("occurredAt"));
        assertEquals(ACTOR.toString(), data.get("actorId"));
        assertEquals("moderator", data.get("actorRole"));
        assertEquals("seller.approved", data.get("action"));
        assertEquals("seller", data.get("objectType"));
        assertEquals("0199e0a0-0000-7000-8000-000000000101", data.get("objectId"));
        assertEquals("203.0.113.7", data.get("actorIp"));
        assertEquals(false, data.get("actorIpHashed"));
    }

    @Test
    void changeIsOneNonPersonalField() {
        Map<String, Object> change = AuditRecords.change("decisionReason", null, "Документ не читается");
        assertEquals(List.of("field", "personal", "before", "after"), List.copyOf(change.keySet()));
        assertEquals(false, change.get("personal"));
        assertTrue(change.containsKey("before") && change.get("before") == null);
        assertEquals("Документ не читается", change.get("after"));
    }

    @Test
    void correlationIdComesFromTheOpenRequestContext() {
        TraceContext context = TraceContext.incoming(null, null);
        TraceContexts.Scope scope = TraceContexts.open(context);
        try {
            assertEquals(context.correlationId(), record("moderator", "seller.approved", "", oneChange()).get("correlationId"));
        } finally {
            scope.close();
        }
        String fresh = (String) record("moderator", "seller.approved", "", oneChange()).get("correlationId");
        UUID.fromString(fresh);
    }

    @Test
    void systemActionMayHaveNoAddress() {
        assertEquals("", record("system", "user.role_assigned", "", oneChange()).get("actorIp"));
    }

    @Test
    void roleOutsideTheSchemaIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> record("root", "seller.approved", "", oneChange()));
        assertThrows(IllegalArgumentException.class, () -> record(null, "seller.approved", "", oneChange()));
    }

    @Test
    void actionMustMatchThePatternOfTheSchema() {
        for (String bad : new String[] {"approved", "Seller.approved", "seller.", "seller.approved-now", "seller..approved", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> record("moderator", bad, "", oneChange()), String.valueOf(bad));
        }
        assertEquals("user.role_assigned", record("system", "user.role_assigned", "", oneChange()).get("action"));
        assertThrows(IllegalArgumentException.class, () -> record("moderator", "seller." + "a".repeat(60), "", oneChange()));
    }

    @Test
    void emptyChangesAndOversizedValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> record("moderator", "seller.approved", "", List.of()));
        assertThrows(IllegalArgumentException.class, () -> record("moderator", "seller.approved", "", null));
        assertThrows(IllegalArgumentException.class, () -> record("moderator", "seller.approved", "x".repeat(129), oneChange()));
        assertThrows(IllegalArgumentException.class, () -> record("moderator", "seller.approved", null, oneChange()));
        assertThrows(IllegalArgumentException.class,
                () -> AuditRecords.staffAction(AT, ACTOR, "moderator", "", "seller.approved", " ", "id", oneChange()));
        assertThrows(IllegalArgumentException.class,
                () -> AuditRecords.staffAction(AT, ACTOR, "moderator", "", "seller.approved", "seller", "i".repeat(129), oneChange()));
        assertThrows(IllegalArgumentException.class, () -> AuditRecords.change(" ", "a", "b"));
    }

    @Test
    void changesAreCopiedSoALaterEditCannotChangeTheRecord() {
        List<Map<String, Object>> changes = new java.util.ArrayList<>(oneChange());
        Map<String, Object> data = record("moderator", "seller.approved", "", changes);
        changes.clear();
        assertEquals(1, ((List<?>) data.get("changes")).size());
        assertFalse(((List<?>) data.get("changes")).isEmpty());
    }

    @Test
    void topicAggregateAndEventNamesAreThoseOfTheContract() {
        assertEquals("audit.events", AuditRecords.TOPIC);
        assertEquals("AuditRecord", AuditRecords.AGGREGATE);
        assertEquals("audit.recorded", AuditRecords.EVENT);
        assertEquals(1, AuditRecords.SCHEMA_VERSION);
    }
}
