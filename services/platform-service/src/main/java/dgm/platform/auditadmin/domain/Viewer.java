package dgm.platform.auditadmin.domain;

import java.util.UUID;

/**
 * Кто открывает журнал: для записи о просмотре (чтение это действие, ADR-014, Р-166).
 *
 * @param role адрес берётся как есть: у сотрудника он не персональный (ADR-014); пустая строка, если адреса нет
 */
public record Viewer(UUID id, String role, String ip) {
}
