package dgm.kit.outbox;

import java.time.Duration;

/**
 * Параметры публикатора (ADR-005, раздел «Публикатор»).
 *
 * @param pollInterval пауза между циклами, когда очередь пуста: 200 мс
 * @param batchSize    размер пачки: 100 строк
 * @param maxAttempts  число неудач, после которого строка паркуется: 10
 * @param maxBackoff   предел паузы после неудачи: 30 с (пауза растёт 1, 2, 4 ... с)
 * @param lockKey      ключ консультативной блокировки: одинаков у всех экземпляров сервиса, блокировки действуют в пределах базы
 */
public record OutboxSettings(Duration pollInterval, int batchSize, int maxAttempts, Duration maxBackoff, long lockKey) {

    /** Ключ блокировки по умолчанию: ASCII-строка «DGMOUTBX». */
    public static final long DEFAULT_LOCK_KEY = 0x44474D4F5554425AL;

    public OutboxSettings {
        if (batchSize < 1 || maxAttempts < 1 || pollInterval.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("Параметры публикатора заданы неверно");
        }
    }

    public static OutboxSettings defaults() {
        return new OutboxSettings(Duration.ofMillis(200), 100, 10, Duration.ofSeconds(30), DEFAULT_LOCK_KEY);
    }

    /** Пауза после неудачи номер {@code failures} (с 1): 1, 2, 4 ... секунд, не больше предела. */
    public Duration backoff(int failures) {
        long seconds = 1L << Math.min(Math.max(failures - 1, 0), 20);
        Duration d = Duration.ofSeconds(seconds);
        return d.compareTo(maxBackoff) > 0 ? maxBackoff : d;
    }
}
