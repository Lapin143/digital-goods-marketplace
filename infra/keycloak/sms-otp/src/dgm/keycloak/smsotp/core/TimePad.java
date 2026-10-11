package dgm.keycloak.smsotp.core;

import java.time.Duration;

/**
 * Выравнивание времени ответа: если работа закончилась раньше минимума, ждём до минимума. Нужно, чтобы по времени ответа нельзя было
 * отличить ветки «адрес свободен» и «адрес занят» (создание учётной записи в базе дольше, чем отправка одного письма). Если работа
 * заняла больше минимума, ничего не добавляется: ветки выполняют одинаковый объём работы (хеширование пароля, письмо), и разница мала.
 */
public final class TimePad {

    private TimePad() {
    }

    /**
     * @param startedNanos момент начала работы по {@link System#nanoTime()}
     * @param minimum      минимальная длительность всего ответа
     */
    public static void padTo(long startedNanos, Duration minimum) {
        long remaining = minimum.toNanos() - (System.nanoTime() - startedNanos);
        if (remaining <= 0) {
            return;
        }
        try {
            Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
