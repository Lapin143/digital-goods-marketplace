package dgm.platform.auditadmin.testing;

import dgm.kit.outbox.AuditRecords;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Данные события {@code audit.recorded} для тестов: те же поля, что собирает {@link AuditRecords}, и их порча по одному полю. */
public final class AuditData {

    public static final UUID ACTOR = UUID.fromString("5d3b1f79-8c2e-4a60-b4d8-6e0a2c4f1b37");
    public static final Instant AT = Instant.parse("2026-10-25T12:30:00.250Z");
    public static final String HASH_A = "a".repeat(64);
    public static final String HASH_B = "0123456789abcdef".repeat(4);

    private AuditData() {
    }

    /** Действие сотрудника: адрес как есть, одно неперсональное изменение. */
    public static Map<String, Object> staff() {
        return new LinkedHashMap<>(AuditRecords.staffAction(AT, ACTOR, "moderator", "203.0.113.7", "seller.approved", "seller",
                "0199e0a0-0000-7000-8000-000000000101", List.of(AuditRecords.change("status", "on_review", "approved"))));
    }

    /** Действие самого пользователя: адрес хешем, персональное поле хешами. */
    public static Map<String, Object> user() {
        Map<String, Object> data = staff();
        data.put("actorRole", "buyer");
        data.put("action", "user.phone_confirmed");
        data.put("objectType", "user");
        data.put("changes", List.of(AuditRecords.personalChange("phone", null, HASH_A), AuditRecords.change("phone_confirmed", false, true)));
        data.put("actorIp", HASH_B);
        data.put("actorIpHashed", true);
        return data;
    }

    public static Map<String, Object> change(String field, boolean personal, Object before, Object after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", field);
        change.put("personal", personal);
        change.put("before", before);
        change.put("after", after);
        return change;
    }
}
