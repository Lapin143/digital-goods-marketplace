package dgm.inventory.inventory.reservation.domain;

import dgm.kit.config.ConfigRegistry;
import dgm.kit.config.ConfigValue;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Настройки резерва. Ключи совпадают с ключами параметров платформы (Р-10, правило 14 руководства среза 2): срок резерва
 * {@code reservation.ttl-seconds} берётся из {@code application.yml}, пока слушатель {@code platform.config} ничего не принёс, и при
 * недоступной Kafka. {@link #live} подключает настройки к реестру параметров: каждое обращение к {@link #ttl()} читает текущее значение
 * (правило 19 руководства среза 3), смена параметра меняет только новые резервы, созданные раньше сохраняют свой срок. Профиль
 * {@code fast-time} меняет только значения настроек (и закрепляет ключ), код остаётся тем же.
 */
public final class ReservationSettings {

    public static final String TTL_KEY = "reservation.ttl-seconds";
    public static final String GRACE_KEY = "dgm.reservation.reconcile-grace-seconds";

    private final Supplier<Duration> ttl;
    private final Duration reconcileGrace;

    /**
     * Неизменяемые настройки.
     *
     * @param ttl            срок резерва от создания до истечения; от 1 секунды до суток (INV-07: платёжная сессия короче резерва)
     * @param reconcileGrace насколько срок должен быть просрочен, чтобы сверка снимала резерв сама (таймер Redis потерян, ADR-012);
     *                       короче этого срока резерв остаётся таймеру
     */
    public ReservationSettings(Duration ttl, Duration reconcileGrace) {
        if (ttl == null || ttl.toSeconds() < 1 || ttl.toSeconds() > 86_400) {
            throw new IllegalArgumentException("Параметр " + TTL_KEY + " должен быть от 1 секунды до суток, а задано: " + ttl);
        }
        this.ttl = () -> ttl;
        this.reconcileGrace = checkedGrace(reconcileGrace);
    }

    private ReservationSettings(ConfigValue ttl, Duration reconcileGrace) {
        this.ttl = ttl::seconds;
        this.reconcileGrace = reconcileGrace;
    }

    private static Duration checkedGrace(Duration reconcileGrace) {
        if (reconcileGrace == null || reconcileGrace.isNegative() || reconcileGrace.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Параметр " + GRACE_KEY + " должен быть от 0 до часа, а задано: " + reconcileGrace);
        }
        return reconcileGrace;
    }

    public static ReservationSettings defaults() {
        return new ReservationSettings(Duration.ofMinutes(15), Duration.ofSeconds(30));
    }

    /** Живые настройки: срок резерва регистрируется в реестре со значением {@code initial} и границами от 1 секунды до суток. */
    public static ReservationSettings live(ConfigRegistry registry, ReservationSettings initial) {
        ConfigValue ttl = registry.register(TTL_KEY, initial.ttl().toSeconds(), 1, 86_400);
        return new ReservationSettings(ttl, initial.reconcileGrace());
    }

    /** Срок резерва от создания до истечения. */
    public Duration ttl() {
        return ttl.get();
    }

    public Duration reconcileGrace() {
        return reconcileGrace;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReservationSettings that && ttl().equals(that.ttl()) && reconcileGrace.equals(that.reconcileGrace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ttl(), reconcileGrace);
    }

    @Override
    public String toString() {
        return "ReservationSettings[ttl=" + ttl() + ", reconcileGrace=" + reconcileGrace + "]";
    }
}
