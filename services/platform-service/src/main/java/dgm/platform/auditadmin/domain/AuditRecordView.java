package dgm.platform.auditadmin.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Запись журнала для показа администратору (схема {@code AuditRecord}). Персональных значений в записи нет: у персональных полей только
 * хеши, у адреса пользователя тоже (ADR-014).
 *
 * @param actorIp адрес исполнителя; пустая строка, если адреса нет (действие системы)
 */
public record AuditRecordView(UUID recordId, Instant occurredAt, UUID actorId, String actorRole, String action, String objectType,
                              String objectId, List<Change> changes, String actorIp, boolean actorIpHashed, UUID correlationId) {

    public AuditRecordView {
        changes = List.copyOf(changes);
    }

    /**
     * Изменение одного поля. Контракт описывает значения строками (или пусто), поэтому числа и логические значения журнала здесь уже
     * строки: {@code 900}, {@code true} (F4-29).
     */
    public record Change(String field, boolean personal, String before, String after) {
    }
}
