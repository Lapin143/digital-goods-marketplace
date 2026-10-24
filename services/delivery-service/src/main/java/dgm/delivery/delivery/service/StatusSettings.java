package dgm.delivery.delivery.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Настройки статусов писем: с какого возраста выдача «отправлено» без статуса попадает в опрос провайдера, сколько выдач опрос берёт за
 * проход и сколько контролей 30 минут исполнитель берёт за проход. Ключи {@code dgm.delivery.poller.min-age}, {@code dgm.delivery.poller.batch},
 * {@code dgm.delivery.watch.batch}; интервалы проходов задают ключи {@code dgm.delivery.poller.interval} и {@code dgm.delivery.watch.interval}.
 *
 * @param pollAfter  возраст «отправлено», с которого статус спрашивается у провайдера (5 минут, ADR-011); в {@code fast-time} 10 секунд
 * @param pollBatch  сколько выдач опрашивается за проход (50)
 * @param watchBatch сколько контролей исполнитель берёт за проход (50)
 */
public record StatusSettings(Duration pollAfter, int pollBatch, int watchBatch) {

    public StatusSettings {
        Objects.requireNonNull(pollAfter, "pollAfter");
        if (pollAfter.isNegative()) {
            throw new IllegalArgumentException("Возраст выдачи для опроса не бывает отрицательным");
        }
        if (pollBatch < 1 || pollBatch > 1000) {
            throw new IllegalArgumentException("Пачка опроса от 1 до 1000");
        }
        if (watchBatch < 1 || watchBatch > 1000) {
            throw new IllegalArgumentException("Пачка контролей от 1 до 1000");
        }
    }

    /** Значения архитектуры. */
    public static StatusSettings defaults() {
        return new StatusSettings(Duration.ofMinutes(5), 50, 50);
    }
}
