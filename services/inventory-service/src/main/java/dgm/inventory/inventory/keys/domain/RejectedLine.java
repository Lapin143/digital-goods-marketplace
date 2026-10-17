package dgm.inventory.inventory.keys.domain;

/**
 * Отклонённая строка загрузки: номер с единицы и причина, без значения.
 *
 * @param line   номер строки файла или позиции в списке
 * @param reason причина отказа
 */
public record RejectedLine(int line, RejectReason reason) {
}
