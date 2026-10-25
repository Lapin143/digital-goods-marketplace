package dgm.platform.auditadmin.repository;

import dgm.platform.auditadmin.domain.AuditEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Журнал аудита под ролью {@code app_audit_writer}: добавление записей и обслуживание секций. Изменить или удалить запись нельзя ни здесь,
 * ни в базе (триггеры {@code audit_log_deny}, INV-42), поэтому в интерфейсе таких методов нет.
 */
public interface AuditLogRepository {

    /**
     * Добавляет запись, если события с таким {@code eventId} и временем действия в журнале ещё нет.
     *
     * @return {@code true}: запись добавлена; {@code false}: это повторная доставка, журнал не изменился
     */
    boolean insert(UUID id, UUID eventId, AuditEntry entry, Instant recordedAt);

    /**
     * Создаёт месячные секции с {@code first} на {@code months} месяцев вперёд (включая первый) под консультативной блокировкой: два экземпляра
     * сервиса не создают секцию одновременно.
     *
     * @param first первое число месяца, с которого начинаются секции
     * @return сколько секций проверено или создано; ноль значит, что блокировку держит другой экземпляр
     */
    int ensurePartitions(LocalDate first, int months);

    /** Число строк страховочной секции, но не больше 1000: строки там быть не должно (время действия вне созданных месяцев). */
    long defaultPartitionRows();
}
