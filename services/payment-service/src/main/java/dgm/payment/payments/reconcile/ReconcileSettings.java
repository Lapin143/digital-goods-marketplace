package dgm.payment.payments.reconcile;

import java.time.Duration;

/**
 * Границы сверки со шлюзом (ADR-015): платежи «создан» моложе {@code youngAge} проверяются часто, до {@code maxAge} реже, старше
 * {@code maxAge} попадают в ежедневный отчёт. Интервалы запусков задаются ключами {@code dgm.payment.reconcile.*} в
 * {@code application.yml} (в профиле {@code fast-time} они короче).
 *
 * @param youngAge   возраст, до которого платёж проверяется часто (2 часа)
 * @param maxAge     возраст, после которого платёж уходит в отчёт (24 часа)
 * @param alertAfter через какое время несопоставленное уведомление требует оповещения (1 час)
 * @param batch      сколько платежей обрабатывается за один проход
 */
public record ReconcileSettings(Duration youngAge, Duration maxAge, Duration alertAfter, int batch) {

    public ReconcileSettings {
        if (youngAge == null || youngAge.isZero() || youngAge.isNegative() || maxAge == null || maxAge.compareTo(youngAge) <= 0) {
            throw new IllegalStateException("Возраст платежей сверки: от нуля до youngAge часто, до maxAge реже, maxAge больше youngAge");
        }
        if (alertAfter == null || alertAfter.isNegative() || batch < 1) {
            throw new IllegalStateException("Срок оповещения не отрицателен, размер прохода не меньше единицы");
        }
    }

    public static ReconcileSettings defaults() {
        return new ReconcileSettings(Duration.ofHours(2), Duration.ofHours(24), Duration.ofHours(1), 100);
    }
}
