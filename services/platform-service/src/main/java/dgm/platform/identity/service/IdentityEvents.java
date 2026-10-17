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
public final class IdentityEvents {

    public static final String USER_TOPIC = "identity.events";
    public static final String USER_AGGREGATE = "User";
    public static final String AUDIT_TOPIC = "audit.events";
    public static final String AUDIT_AGGREGATE = "AuditRecord";
    public static final String AUDIT_EVENT = "audit.recorded";

    public static final String REGISTERED = "user.registered";
    public static final String ROLE_ASSIGNED = "user.role-assigned";
    public static final String DEACTIVATED = "user.deactivated";
    public static final String ANONYMIZED = "user.anonymized";
    public static final String PHONE_CONFIRMED = "user.phone-confirmed";
    public static final String SMS_LOGIN = "user.sms-login";

    public static final String AUDIT_ROLE_ASSIGNED = "user.role_assigned";
    public static final String AUDIT_DEACTIVATED = "user.deactivated";
    public static final String AUDIT_ANONYMIZED = "user.anonymized";
    public static final String AUDIT_PHONE_CONFIRMED = "user.phone_confirmed";

    private IdentityEvents() {
    }

    public static Map<String, Object> registered(UUID userId, String role, String registrationMethod) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("role", role);
        data.put("registrationMethod", registrationMethod);
        return data;
    }

    public static Map<String, Object> roleAssigned(UUID userId, String role, UUID assignedBy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("role", role);
        data.put("source", "admin");
        data.put("assignedBy", assignedBy.toString());
        return data;
    }

    /** Роль продавца назначена по решению модератора: {@code assignedBy} отсутствует (схема {@code UserRoleAssignedData}). */
    public static Map<String, Object> sellerRoleAssigned(UUID userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("role", "seller");
        data.put("source", "seller_approval");
        return data;
    }

    public static Map<String, Object> deactivated(UUID userId, UUID deactivatedBy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("deactivatedBy", deactivatedBy.toString());
        return data;
    }

    public static Map<String, Object> anonymized(UUID userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("requestedBy", "admin");
        return data;
    }

    /** {@code UserPhoneConfirmedData}: только идентификатор, номера в событии нет. */
    public static Map<String, Object> phoneConfirmed(UUID userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        return data;
    }

    /** {@code UserSmsLoginData}: только идентификатор, номера и кода в событии нет. */
    public static Map<String, Object> smsLogin(UUID userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        return data;
    }

    /** Изменение одного неперсонального поля: значения идут как есть, хеши не нужны. */
    public static Map<String, Object> change(String field, Object before, Object after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", field);
        change.put("personal", false);
        change.put("before", before);
        change.put("after", after);
        return change;
    }

    /** Изменение персонального поля: в журнал идут только HMAC-хеши значений (ADR-014), {@code before} пуст при первом заполнении. */
    public static Map<String, Object> personalChange(String field, String beforeHash, String afterHash) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", field);
        change.put("personal", true);
        change.put("before", beforeHash);
        change.put("after", afterHash);
        return change;
    }

    /**
     * Запись аудита о действии самого пользователя над своей записью: адрес в журнал идёт хешем (у пользователя, в отличие от сотрудника,
     * он персональный, ADR-014).
     */
    public static Map<String, Object> userAudit(Instant at, UUID actorId, String actorRole, String action, UUID userId,
                                                List<Map<String, Object>> changes, String ipHash) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("occurredAt", Timestamps.format(at));
        data.put("actorId", actorId.toString());
        data.put("actorRole", actorRole);
        data.put("action", action);
        data.put("objectType", "user");
        data.put("objectId", userId.toString());
        data.put("changes", changes);
        data.put("actorIp", ipHash);
        data.put("actorIpHashed", true);
        data.put("correlationId", TraceContexts.currentOrFresh().correlationId());
        return data;
    }

    /** Запись для журнала аудита (ADR-014): кто, когда, что и над кем; персональных полей нет, поэтому хеши не нужны. */
    public static Map<String, Object> audit(Instant at, Actor actor, String action, UUID userId, List<Map<String, Object>> changes) {
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
