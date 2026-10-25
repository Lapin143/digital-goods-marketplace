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

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "0123456789abcdef".repeat(4);

    @Test
    void personalChangeCarriesReadyHashesAndTheFlag() {
        Map<String, Object> change = AuditRecords.personalChange("email", HASH_A, HASH_B);
        assertEquals(List.of("field", "personal", "before", "after"), List.copyOf(change.keySet()));
        assertEquals(true, change.get("personal"));
        assertEquals(HASH_A, change.get("before"));
        assertEquals(HASH_B, change.get("after"));
        Map<String, Object> first = AuditRecords.personalChange("phone", null, HASH_A);
        assertTrue(first.containsKey("before") && first.get("before") == null, "при первом заполнении «до» пусто");
    }

    @Test
    void personalChangeRejectsAnythingButAHashAndDoesNotEchoTheValue() {
        for (String bad : new String[] {"ivan@mail.example", "+79990001122", "A".repeat(64), "a".repeat(63), "a".repeat(65), "", " "}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AuditRecords.personalChange("email", null, bad));
            assertFalse(bad.length() > 3 && e.getMessage().contains(bad), "значение не попадает в сообщение об ошибке");
            assertThrows(IllegalArgumentException.class, () -> AuditRecords.personalChange("email", bad, HASH_A));
        }
        assertThrows(IllegalArgumentException.class, () -> AuditRecords.personalChange(" ", HASH_A, HASH_B));
        assertThrows(IllegalArgumentException.class, () -> AuditRecords.personalChange(null, HASH_A, HASH_B));
    }

    @Test
    void isHashAcceptsOnlyLowercaseHexOfSixtyFourChars() {
        assertTrue(AuditRecords.isHash(HASH_A));
        assertTrue(AuditRecords.isHash(HASH_B));
        assertFalse(AuditRecords.isHash(null));
        assertFalse(AuditRecords.isHash("g".repeat(64)));
        assertFalse(AuditRecords.isHash(HASH_A + "\n"));
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

    /** Таблица журнала принимает тип объекта только строчными буквами: «Payment» из платежей уходил бы в DLQ (F4-28). */
    @Test
    void objectTypeMustMatchTheJournalRule() {
        assertTrue(AuditRecords.isObjectType("payment"));
        assertTrue(AuditRecords.isObjectType("seller.document"));
        assertFalse(AuditRecords.isObjectType("Payment"));
        assertFalse(AuditRecords.isObjectType("с пробелом"));
        assertFalse(AuditRecords.isObjectType(""));
        assertFalse(AuditRecords.isObjectType("a".repeat(65)));
        assertFalse(AuditRecords.isObjectType(null));
        assertThrows(IllegalArgumentException.class,
                () -> AuditRecords.staffAction(AT, ACTOR, "admin", "", "refund.marked_manual", "Payment", "id", oneChange()));
    }

    /** Чтение это тоже действие (Р-166): у просмотра журнала изменений нет, у открытия документа одно, остальные правила те же, что у действия. */
    @Test
    void readingMayHaveNoChangesButFollowsTheOtherRulesOfTheAction() {
        Map<String, Object> view = AuditRecords.staffRead(AT, ACTOR, "admin", "203.0.113.7", "audit.viewed", "audit-log", "journal", List.of());
        assertEquals(SCHEMA_FIELDS, view.keySet());
        assertEquals(List.of(), view.get("changes"));
        assertEquals("audit.viewed", view.get("action"));
        assertEquals(false, view.get("actorIpHashed"));
        Map<String, Object> opened = AuditRecords.staffRead(AT, ACTOR, "moderator", "203.0.113.7", "seller.document_opened", "seller", "id",
                List.of(AuditRecords.change("documentId", null, "0199e0a0-0000-7000-8000-000000000201")));
        assertEquals(1, ((List<?>) opened.get("changes")).size());
        assertEquals(List.of(), AuditRecords.staffRead(AT, ACTOR, "admin", "", "audit.viewed", "audit-log", "journal", null).get("changes"));
        assertThrows(IllegalArgumentException.class, () -> AuditRecords.staffRead(AT, ACTOR, "guest", "", "audit.viewed", "audit-log", "journal", List.of()));
        assertThrows(IllegalArgumentException.class, () -> AuditRecords.staffRead(AT, ACTOR, "admin", "", "Audit.Viewed", "audit-log", "journal", List.of()));
        assertThrows(IllegalArgumentException.class, () -> AuditRecords.staffRead(AT, ACTOR, "admin", "", "audit.viewed", "AuditLog", "journal", List.of()));
        assertThrows(IllegalArgumentException.class, () -> AuditRecords.staffRead(AT, ACTOR, "admin", null, "audit.viewed", "audit-log", "journal", List.of()));
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
