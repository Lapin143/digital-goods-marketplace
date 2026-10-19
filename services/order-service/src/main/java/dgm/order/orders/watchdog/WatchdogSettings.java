package dgm.order.orders.watchdog;

import java.time.Duration;

/**
 * Настройки сторожа заказов (ADR-004, «Таймеры оркестратора»). Интервалы запусков задают ключи {@code dgm.order.watchdog.interval} и
 * {@code initial-delay} в {@code application.yml}; пороги не зашиты в код (правило 14 руководства среза 2) и сокращаются профилем
 * {@code fast-time}.
 *
 * @param createdTimeout заказ «создан» старше этого срока потерял продолжение (ответ резерва или платёжной сессии, падение процесса): 60 секунд
 * @param reserveGrace   заказ «ожидает оплаты» с резервом, истёкшим раньше этого срока назад, потерял событие {@code reservation.expired}: 5 минут
 * @param batch          сколько заказов каждого вида сторож берёт за проход
 */
public record WatchdogSettings(Duration createdTimeout, Duration reserveGrace, int batch) {

    public WatchdogSettings {
        if (createdTimeout == null || createdTimeout.isZero() || createdTimeout.isNegative()) {
            throw new IllegalStateException("Срок заказа «создан» для сторожа должен быть больше нуля");
        }
        if (reserveGrace == null || reserveGrace.isNegative()) {
            throw new IllegalStateException("Запас сторожа после конца резерва не может быть отрицательным");
        }
        if (batch < 1 || batch > 500) {
            throw new IllegalStateException("Размер прохода сторожа заказов от 1 до 500");
        }
    }

    public static WatchdogSettings defaults() {
        return new WatchdogSettings(Duration.ofSeconds(60), Duration.ofMinutes(5), 100);
    }
}
