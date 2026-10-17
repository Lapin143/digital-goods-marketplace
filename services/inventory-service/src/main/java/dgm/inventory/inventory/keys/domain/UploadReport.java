package dgm.inventory.inventory.keys.domain;

import java.util.List;

/**
 * Итог загрузки (схема {@code KeyUploadReport}): сколько принято, сколько отклонено и первые отклонённые строки, остаток после загрузки и
 * предел пула. Значений ключей в отчёте нет.
 *
 * @param accepted      принято и сохранено в статусе «свободен»
 * @param rejectedTotal отклонено строк
 * @param rejected      первые {@value #MAX_REJECTED} отклонённых строк по возрастанию номера
 * @param available     свободные ключи после загрузки
 * @param poolLimit     предел пула одного товара
 */
public record UploadReport(int accepted, int rejectedTotal, List<RejectedLine> rejected, int available, int poolLimit) {

    /** Сколько отклонённых строк показывает отчёт. */
    public static final int MAX_REJECTED = 1000;
}
