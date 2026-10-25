package dgm.platform.auditadmin.service;

import java.util.Map;

/**
 * Запись события «журнал просмотрен» в Outbox отдельной короткой транзакцией модуля {@code audit_admin} (ADR-005). Чтение журнала своих
 * изменений данных не делает, поэтому транзакции вокруг него нет: запись об открытии должна быть зафиксирована до того, как данные
 * уйдут клиенту. Отдельный интерфейс нужен, чтобы службу проверяли модульными тестами без базы.
 */
public interface AuditViewOutbox {

    /** Фиксирует событие; любое исключение значит, что события нет. */
    void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data);
}
