package dgm.inventory.inventory.reservation.domain;

import java.time.Duration;

/**
 * Настройки резерва. Ключи совпадают с ключами параметров платформы (Р-10, правило 14 руководства среза 2): срок резерва
 * {@code reservation.ttl-seconds} читается из {@code application.yml}, а до слушателя {@code config.changed} (срез 3) значение по
 * умолчанию задаёт {@link #defaults()}. Профиль {@code fast-time} меняет только значения настроек, код остаётся тем же.
 *
 * @param ttl            срок резерва от создания до истечения; от 1 секунды до суток (INV-07: платёжная сессия короче резерва)
 * @param reconcileGrace насколько срок должен быть просрочен, чтобы сверка снимала резерв сама (таймер Redis потерян, ADR-012);
 *                       короче этого срока резерв остаётся таймеру
 */
public record ReservationSettings(Duration ttl, Duration reconcileGrace) {

    public static final String TTL_KEY = "reservation.ttl-seconds";
    public static final String GRACE_KEY = "dgm.reservation.reconcile-grace-seconds";

    public ReservationSettings {
        if (ttl == null || ttl.toSeconds() < 1 || ttl.toSeconds() > 86_400) {
            throw new IllegalArgumentException("Параметр " + TTL_KEY + " должен быть от 1 секунды до суток, а задано: " + ttl);
        }
        if (reconcileGrace == null || reconcileGrace.isNegative() || reconcileGrace.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Параметр " + GRACE_KEY + " должен быть от 0 до часа, а задано: " + reconcileGrace);
        }
    }

    public static ReservationSettings defaults() {
        return new ReservationSettings(Duration.ofMinutes(15), Duration.ofSeconds(30));
    }
}
