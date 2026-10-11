package dgm.platform.identity.service;

import dgm.kit.time.Timestamps;
import dgm.kit.trace.TraceContexts;
import dgm.platform.identity.domain.Actor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Данные событий модуля по схемам AsyncAPI ({@code UserRegisteredData}, {@code UserRoleAssignedData}, {@code UserDeactivatedData},
 * {@code UserAnonymizedData}, {@code AuditRecordedData}). Лишних полей нет: схемы закрыты ({@code additionalProperties: false}), а
 * персональных данных в событиях нет вовсе, только идентификаторы.
 */
final class IdentityEvents {

    static final String USER_TOPIC = "identity.events";
    static final String USER_AGGREGATE = "User";
    static final String AUDIT_TOPIC = "audit.events";
    static final String AUDIT_AGGREGATE = "AuditRecord";
    static final String AUDIT_EVENT = "audit.recorded";

    static final String REGISTERED = "user.registered";
    static final String ROLE_ASSIGNED = "user.role-assigned";
    static final String DEACTIVATED = "user.deactivated";
    static final String ANONYMIZED = "user.anonymized";

    static final String AUDIT_ROLE_ASSIGNED = "user.role_assigned";
    static final String AUDIT_DEACTIVATED = "user.deactivated";
    static final String AUDIT_ANONYMIZED = "user.anonymized";

    private IdentityEvents() {
    }

    static Map<String, Object> registered(UUID userId, String role, String registrationMethod) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("role", role);
        data.put("registrationMethod", registrationMethod);
        return data;
    }

    static Map<String, Object> roleAssigned(UUID userId, String role, UUID assignedBy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("role", role);
        data.put("source", "admin");
        data.put("assignedBy", assignedBy.toString());
        return data;
    }

    static Map<String, Object> deactivated(UUID userId, UUID deactivatedBy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("deactivatedBy", deactivatedBy.toString());
        return data;
    }

    static Map<String, Object> anonymized(UUID userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("requestedBy", "admin");
        return data;
    }

    /** Изменение одного неперсонального поля: значения идут как есть, хеши не нужны. */
    static Map<String, Object> change(String field, Object before, Object after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", field);
        change.put("personal", false);
        change.put("before", before);
        change.put("after", after);
        return change;
    }

    /** Запись для журнала аудита (ADR-014): кто, когда, что и над кем; персональных полей нет, поэтому хеши не нужны. */
    static Map<String, Object> audit(Instant at, Actor actor, String action, UUID userId, List<Map<String, Object>> changes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("occurredAt", Timestamps.format(at));
        data.put("actorId", actor.id().toString());
        data.put("actorRole", actor.role());
        data.put("action", action);
        data.put("objectType", "user");
        data.put("objectId", userId.toString());
        data.put("changes", changes);
        data.put("actorIp", actor.ip());
        data.put("actorIpHashed", false);
        data.put("correlationId", TraceContexts.currentOrFresh().correlationId());
        return data;
    }
}
