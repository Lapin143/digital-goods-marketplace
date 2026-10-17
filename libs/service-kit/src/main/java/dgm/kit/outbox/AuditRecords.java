package dgm.kit.outbox;

import dgm.kit.time.Timestamps;
import dgm.kit.trace.TraceContexts;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Данные события {@code audit.recorded} по схеме {@code AuditRecordedData} (ADR-014, AsyncAPI). Сервис пишет такое событие в Outbox в
 * той же транзакции, что и действие сотрудника (INV-43), а журнал аудита платформы принимает его из темы {@code audit.events}.
 * Схема закрыта (лишних полей нет), поэтому поля собираются здесь, а не в каждом сервисе по-своему.
 *
 * <p>Персональных полей этот помощник не принимает: действия сотрудников над заявкой и товаром несут идентификаторы, статусы и причины
 * решений, а все они не персональные (ADR-014). Для персональных значений владелец данных сам считает хеш и строит запись по-своему.
 */
public final class AuditRecords {

    public static final String TOPIC = "audit.events";
    public static final String AGGREGATE = "AuditRecord";
    public static final String EVENT = "audit.recorded";
    public static final int SCHEMA_VERSION = 1;

    /** Роли исполнителя из схемы события. */
    private static final Set<String> ROLES = Set.of("buyer", "seller", "moderator", "support-operator", "admin", "system");
    private static final Pattern ACTION = Pattern.compile("^[a-z]+(\\.[a-z_]+)+$");
    private static final int MAX_ACTION = 64;
    private static final int MAX_OBJECT_TYPE = 64;
    private static final int MAX_OBJECT_ID = 128;
    private static final int MAX_IP = 128;

    private AuditRecords() {
    }

    /** Изменение одного неперсонального поля: значения идут как есть. Значение {@code null} значит «поля не было» или «поле очищено». */
    public static Map<String, Object> change(String field, Object before, Object after) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("У изменения нет имени поля");
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", field);
        change.put("personal", false);
        change.put("before", before);
        change.put("after", after);
        return change;
    }

    /**
     * Действие сотрудника: адрес записывается как есть (у сотрудника он не персональный, ADR-014), сквозной идентификатор берётся из
     * текущего контекста запроса.
     *
     * @param at         момент действия по часам сервиса
     * @param actorId    идентификатор пользователя, выполнившего действие
     * @param actorRole  роль в момент действия: {@code moderator}, {@code admin}, {@code support-operator}, {@code system}
     * @param actorIp    адрес исполнителя; пустая строка, если действие выполнила система и адреса нет
     * @param action     код действия вида {@code seller.approved} (строчные буквы, точка, не длиннее 64 знаков)
     * @param objectType тип объекта действия
     * @param objectId   идентификатор объекта
     * @param changes    изменения полей, не пустой список из {@link #change}
     */
    public static Map<String, Object> staffAction(Instant at, UUID actorId, String actorRole, String actorIp, String action, String objectType,
                                                  String objectId, List<Map<String, Object>> changes) {
        if (!ROLES.contains(actorRole)) {
            throw new IllegalArgumentException("Роль исполнителя вне схемы события: " + actorRole);
        }
        if (action == null || action.length() > MAX_ACTION || !ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("Код действия не подходит под схему события: " + action);
        }
        requireText(objectType, MAX_OBJECT_TYPE, "тип объекта");
        requireText(objectId, MAX_OBJECT_ID, "идентификатор объекта");
        if (actorIp == null || actorIp.length() > MAX_IP) {
            throw new IllegalArgumentException("Адрес исполнителя должен быть строкой не длиннее " + MAX_IP + " знаков");
        }
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("Действие без изменений в журнал не пишется");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("occurredAt", Timestamps.format(at));
        data.put("actorId", actorId.toString());
        data.put("actorRole", actorRole);
        data.put("action", action);
        data.put("objectType", objectType);
        data.put("objectId", objectId);
        data.put("changes", List.copyOf(changes));
        data.put("actorIp", actorIp);
        data.put("actorIpHashed", false);
        data.put("correlationId", TraceContexts.currentOrFresh().correlationId());
        return data;
    }

    private static void requireText(String value, int max, String what) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("Нужен " + what + " длиной от 1 до " + max + " знаков");
        }
    }
}
