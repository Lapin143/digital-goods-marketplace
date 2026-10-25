package dgm.platform.auditadmin.domain;

import dgm.kit.outbox.AuditRecords;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Запись журнала аудита, разобранная из данных события {@code audit.recorded} (схема {@code AuditRecordedData}, ADR-014). Разбор
 * проверяет форму так же строго, как ограничения таблицы {@code audit_admin.audit_log}: запись, которую база отвергла бы, не должна
 * доходить до неё (повтор ничего не исправил бы, поэтому событие сразу уходит в DLQ, Р-164 среза 3).
 *
 * <p>Главная проверка: у персонального поля {@code before} и {@code after} это только HMAC-хеш или {@code null}. Хеш считает владелец
 * данных ({@code AuditHasher}), потребитель журнала хешей не считает и секрета не знает. Сообщения об ошибках называют поля, но не
 * значения: открытый e-mail из отклонённого события не должен попасть в журнал приложения.
 *
 * @param actorIp адрес исполнителя; {@code null}, если действие выполнила система и адреса нет
 */
public record AuditEntry(Instant occurredAt, UUID actorId, String actorRole, String action, String objectType, String objectId,
                         List<Change> changes, String actorIp, boolean actorIpHashed, UUID correlationId) {

    private static final Set<String> FIELDS = Set.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes",
            "actorIp", "actorIpHashed", "correlationId");
    private static final Set<String> ROLES = Set.of("buyer", "seller", "moderator", "support-operator", "admin", "system");
    private static final Pattern ACTION = Pattern.compile("^[a-z]+(\\.[a-z_]+)+$");
    private static final int MAX_ACTION = 64;
    private static final int MAX_OBJECT_ID = 128;
    private static final int MAX_IP = 128;
    private static final int MAX_CHANGES = 50;

    /** Изменение одного поля: {@code before} и {@code after} это строка, число, булево значение или {@code null}; у персонального поля строка-хеш или {@code null}. */
    public record Change(String field, boolean personal, Object before, Object after) {

        private static final Set<String> KEYS = Set.of("field", "personal", "before", "after");
        private static final int MAX_FIELD = 64;

        static Change parse(Object item) {
            if (!(item instanceof Map<?, ?> map) || !KEYS.equals(map.keySet())) {
                throw new IllegalArgumentException("Изменение должно быть объектом с полями field, personal, before, after");
            }
            Object field = map.get("field");
            if (!(field instanceof String name) || name.isBlank() || name.length() > MAX_FIELD) {
                throw new IllegalArgumentException("Имя поля изменения отсутствует или длиннее " + MAX_FIELD + " знаков");
            }
            if (!(map.get("personal") instanceof Boolean personal)) {
                throw new IllegalArgumentException("Признак personal поля " + name + " должен быть булевым значением");
            }
            Object before = value(map.get("before"), name, personal, "before");
            Object after = value(map.get("after"), name, personal, "after");
            return new Change(name, personal, before, after);
        }

        private static Object value(Object raw, String field, boolean personal, String side) {
            if (raw == null) {
                return null;
            }
            if (personal) {
                if (raw instanceof String text && AuditRecords.isHash(text)) {
                    return text;
                }
                throw new IllegalArgumentException("Значение " + side + " персонального поля " + field + " не хеш из 64 шестнадцатеричных знаков");
            }
            if (raw instanceof String || raw instanceof Number || raw instanceof Boolean) {
                return raw;
            }
            throw new IllegalArgumentException("Значение " + side + " поля " + field + " должно быть строкой, числом, булевым значением или null");
        }

        /** Вид изменения для столбца {@code changes} (jsonb). */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("field", field);
            map.put("personal", personal);
            map.put("before", before);
            map.put("after", after);
            return map;
        }
    }

    /**
     * @throws IllegalArgumentException данные не соответствуют схеме события или ограничениям журнала; в сообщении только имена полей
     */
    public static AuditEntry parse(Map<String, Object> data) {
        for (String key : data.keySet()) {
            if (!FIELDS.contains(key)) {
                throw new IllegalArgumentException("Лишнее поле в данных записи аудита: " + key);
            }
        }
        Instant occurredAt = instant(data.get("occurredAt"));
        UUID actorId = uuid(data.get("actorId"), "actorId");
        String role = data.get("actorRole") instanceof String r && ROLES.contains(r) ? r : null;
        if (role == null) {
            throw new IllegalArgumentException("Роль исполнителя вне схемы события");
        }
        String action = text(data.get("action"), "action", MAX_ACTION);
        if (!ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("Код действия не подходит под схему события");
        }
        String objectType = text(data.get("objectType"), "objectType", 64);
        if (!AuditRecords.isObjectType(objectType)) {
            throw new IllegalArgumentException("Тип объекта не подходит под ограничение журнала");
        }
        String objectId = text(data.get("objectId"), "objectId", MAX_OBJECT_ID);
        if (!(data.get("changes") instanceof List<?> items) || items.size() > MAX_CHANGES) {
            throw new IllegalArgumentException("Поле changes должно быть списком не длиннее " + MAX_CHANGES + " изменений");
        }
        List<Change> changes = new ArrayList<>();
        for (Object item : items) {
            changes.add(Change.parse(item));
        }
        if (!(data.get("actorIp") instanceof String ip) || ip.length() > MAX_IP) {
            throw new IllegalArgumentException("Поле actorIp должно быть строкой не длиннее " + MAX_IP + " знаков");
        }
        if (!(data.get("actorIpHashed") instanceof Boolean hashed)) {
            throw new IllegalArgumentException("Поле actorIpHashed должно быть булевым значением");
        }
        if (hashed && !AuditRecords.isHash(ip)) {
            throw new IllegalArgumentException("Адрес пользователя в журнал идёт только хешем из 64 шестнадцатеричных знаков");
        }
        return new AuditEntry(occurredAt, actorId, role, action, objectType, objectId, List.copyOf(changes), ip.isEmpty() ? null : ip, hashed,
                uuid(data.get("correlationId"), "correlationId"));
    }

    /** Содержимое столбца {@code changes} как список объектов. */
    public List<Map<String, Object>> changesAsMaps() {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Change change : changes) {
            maps.add(change.toMap());
        }
        return maps;
    }

    private static Instant instant(Object raw) {
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException("Поле occurredAt отсутствует или не строка");
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Поле occurredAt не разбирается как RFC 3339");
        }
    }

    private static UUID uuid(Object raw, String field) {
        UUID parsed = uuidOrNull(raw);
        if (parsed == null) {
            throw new IllegalArgumentException("Поле " + field + " отсутствует или не UUID");
        }
        return parsed;
    }

    private static UUID uuidOrNull(Object raw) {
        if (!(raw instanceof String text)) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(Object raw, String field, int max) {
        if (!(raw instanceof String text) || text.isEmpty() || text.length() > max) {
            throw new IllegalArgumentException("Поле " + field + " отсутствует или не строка длиной от 1 до " + max + " знаков");
        }
        return text;
    }
}
