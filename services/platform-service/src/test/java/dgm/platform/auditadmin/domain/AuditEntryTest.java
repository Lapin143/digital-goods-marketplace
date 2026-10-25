package dgm.platform.auditadmin.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.platform.auditadmin.testing.AuditData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Разбор записи аудита из события: форма по схеме {@code AuditRecordedData} и правила журнала (INV-42). Открытое персональное значение
 * и адрес пользователя без хеша не принимаются, а в тексте отказа нет значений (он попадает в журнал приложения и заголовок DLQ).
 */
@Tag("TC-813")
@Tag("TC-814")
class AuditEntryTest {

    private static final String OPEN_EMAIL = "ivan.petrov@mail.example";

    private static String rejected(Map<String, Object> data) {
        return assertThrows(IllegalArgumentException.class, () -> AuditEntry.parse(data)).getMessage();
    }

    @Test
    void staffActionIsParsedWithTheAddressAsIs() {
        AuditEntry entry = AuditEntry.parse(AuditData.staff());
        assertEquals(AuditData.AT, entry.occurredAt());
        assertEquals(AuditData.ACTOR, entry.actorId());
        assertEquals("moderator", entry.actorRole());
        assertEquals("seller.approved", entry.action());
        assertEquals("seller", entry.objectType());
        assertEquals("0199e0a0-0000-7000-8000-000000000101", entry.objectId());
        assertEquals("203.0.113.7", entry.actorIp());
        assertFalse(entry.actorIpHashed());
        assertEquals(1, entry.changes().size());
        assertEquals(Map.of("field", "status", "personal", false, "before", "on_review", "after", "approved"), entry.changes().get(0).toMap());
    }

    @Test
    void userActionCarriesOnlyHashesOfThePersonalFieldAndOfTheAddress() {
        AuditEntry entry = AuditEntry.parse(AuditData.user());
        assertTrue(entry.actorIpHashed());
        assertEquals(AuditData.HASH_B, entry.actorIp());
        AuditEntry.Change phone = entry.changes().get(0);
        assertTrue(phone.personal());
        assertNull(phone.before());
        assertEquals(AuditData.HASH_A, phone.after());
    }

    @Test
    void systemActionWithoutAnAddressKeepsNull() {
        Map<String, Object> data = AuditData.staff();
        data.put("actorRole", "system");
        data.put("actorIp", "");
        assertNull(AuditEntry.parse(data).actorIp());
    }

    @Test
    void impersonalValuesMayBeStringsNumbersBooleansAndNull() {
        Map<String, Object> data = AuditData.staff();
        data.put("changes", List.of(AuditData.change("amount", false, null, 1500), AuditData.change("phone_confirmed", false, false, true),
                AuditData.change("rate", false, 1.5d, 2L), AuditData.change("status", false, "a", null)));
        assertEquals(4, AuditEntry.parse(data).changes().size());
    }

    @Test
    void openPersonalValueIsRefusedAndTheMessageDoesNotRepeatIt() {
        for (Object open : List.of(OPEN_EMAIL, "+79991234567", "A".repeat(64), "a".repeat(63), "a".repeat(65), true, 15)) {
            Map<String, Object> data = AuditData.staff();
            data.put("changes", List.of(AuditData.change("email", true, null, open)));
            String message = rejected(data);
            assertTrue(message.contains("email"), message);
            assertFalse(message.contains(OPEN_EMAIL) || message.contains("79991234567"), message);
        }
        Map<String, Object> before = AuditData.staff();
        before.put("changes", List.of(AuditData.change("email", true, OPEN_EMAIL, AuditData.HASH_A)));
        assertFalse(rejected(before).contains(OPEN_EMAIL));
    }

    @Test
    void userAddressMustBeAHashAndAStaffAddressMayBeAnything() {
        Map<String, Object> data = AuditData.user();
        data.put("actorIp", "203.0.113.7");
        String message = rejected(data);
        assertFalse(message.contains("203.0.113.7"), message);
        Map<String, Object> hashedEmpty = AuditData.user();
        hashedEmpty.put("actorIp", "");
        rejected(hashedEmpty);
        Map<String, Object> staff = AuditData.staff();
        staff.put("actorIp", "2001:db8::1");
        assertEquals("2001:db8::1", AuditEntry.parse(staff).actorIp());
    }

    @Test
    void valuesOutsideTheSchemaAreRefused() {
        for (String field : List.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes", "actorIp", "actorIpHashed",
                "correlationId")) {
            Map<String, Object> data = AuditData.staff();
            data.remove(field);
            rejected(data);
        }
        mutate("occurredAt", "вчера");
        mutate("actorId", "not-a-uuid");
        mutate("correlationId", "not-a-uuid");
        mutate("actorRole", "superuser");
        mutate("action", "Seller.Approved");
        mutate("action", "approved");
        mutate("action", "seller." + "a".repeat(60));
        mutate("objectType", "Seller");
        mutate("objectType", "");
        mutate("objectId", "");
        mutate("objectId", "i".repeat(129));
        mutate("changes", "none");
        mutate("actorIp", "x".repeat(129));
        mutate("actorIpHashed", "false");
    }

    private static void mutate(String field, Object value) {
        Map<String, Object> data = AuditData.staff();
        data.put(field, value);
        rejected(data);
    }

    @Test
    void extraFieldsAndMalformedChangesAreRefused() {
        Map<String, Object> extra = AuditData.staff();
        extra.put("email", OPEN_EMAIL);
        assertFalse(rejected(extra).contains(OPEN_EMAIL));

        Map<String, Object> nested = AuditData.staff();
        nested.put("changes", List.of(AuditData.change("x", false, Map.of("a", 1), null)));
        rejected(nested);

        Map<String, Object> noKey = AuditData.staff();
        noKey.put("changes", List.of(Map.of("field", "x", "personal", false, "before", 1)));
        rejected(noKey);

        Map<String, Object> moreKeys = AuditData.staff();
        Map<String, Object> change = AuditData.change("x", false, 1, 2);
        change.put("note", "extra");
        moreKeys.put("changes", List.of(change));
        rejected(moreKeys);

        Map<String, Object> personalFlag = AuditData.staff();
        personalFlag.put("changes", List.of(AuditData.change("x", "yes", 1, 2)));
        rejected(personalFlag);

        Map<String, Object> blankField = AuditData.staff();
        blankField.put("changes", List.of(AuditData.change(" ", false, 1, 2)));
        rejected(blankField);

        Map<String, Object> notObject = AuditData.staff();
        notObject.put("changes", List.of("status"));
        rejected(notObject);

        Map<String, Object> tooMany = AuditData.staff();
        tooMany.put("changes", java.util.Collections.nCopies(51, AuditData.change("x", false, 1, 2)));
        rejected(tooMany);
    }

    @Test
    void emptyListOfChangesIsAcceptedAsTheSchemaAllowsIt() {
        Map<String, Object> data = AuditData.staff();
        data.put("changes", List.of());
        assertEquals(0, AuditEntry.parse(data).changes().size());
    }
}
