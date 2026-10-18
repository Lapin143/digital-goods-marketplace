package dgm.payment.payments.refund;

import java.time.Duration;

/**
 * Настройки исполнителя возвратов. Расписание попыток это отдельный ключ {@code payment.refund-schedule} (параметр платформы); здесь то,
 * что относится к работе исполнителя. Интервалы запусков задают ключи {@code dgm.payment.refund.*} в {@code application.yml}.
 *
 * @param lease         срок аренды попытки: исполнитель не завершил её за это время (процесс упал), и она наступает снова; не меньше
 *                      времени всех вызовов шлюза за проход
 * @param batch         сколько попыток исполнитель берёт за проход
 * @param processingAge через какое время после принятия возврата шлюзом сверка начинает спрашивать его об итоге
 */
public record RefundSettings(Duration lease, int batch, Duration processingAge) {

    public RefundSettings {
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalStateException("Срок аренды попытки возврата должен быть больше нуля");
        }
        if (batch < 1 || batch > 100) {
            throw new IllegalStateException("Размер прохода исполнителя возвратов от 1 до 100");
        }
        if (processingAge == null || processingAge.isNegative()) {
            throw new IllegalStateException("Срок ожидания подтверждения возврата не может быть отрицательным");
        }
    }

    public static RefundSettings defaults() {
        return new RefundSettings(Duration.ofMinutes(2), 10, Duration.ofMinutes(5));
    }
}
