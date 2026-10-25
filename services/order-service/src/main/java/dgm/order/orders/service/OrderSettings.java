package dgm.order.orders.service;

import dgm.kit.config.ConfigRegistry;
import dgm.kit.config.ConfigValue;
import java.time.Duration;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Настройки заказа. Ключи совпадают с параметрами платформы (Р-139). Значения из {@code application.yml} работают, пока слушатель
 * {@code platform.config} ничего не принёс, и при недоступной Kafka; {@link #live} подключает настройки к реестру параметров, и дальше
 * каждое обращение к методу читает текущее значение (правило 19 руководства среза 3): смена параметра на платформе меняет только новые
 * заказы, созданные раньше сохраняют прежние сроки. Сроки не зашиты в код (правило 14 руководства среза 2).
 *
 * <p>INV-07: платёжная сессия строго короче резерва ключей. Нарушение значений из настроек останавливает запуск: покупатель мог бы
 * оплатить заказ, резерв которого уже снят (поздняя оплата с возвратом, ADR-013). Сообщения платформы с нарушением слушатель не применяет.
 */
public final class OrderSettings {

    public static final String MAX_UNPAID_KEY = "orders.max-unpaid";
    public static final String RESERVATION_TTL_KEY = "reservation.ttl-seconds";
    public static final String SESSION_TTL_KEY = "payment-session.ttl-seconds";
    public static final String COMMISSION_KEY = "commission.default-rate-bp";

    private final IntSupplier maxUnpaid;
    private final Supplier<Duration> reservationTtl;
    private final Supplier<Duration> sessionTtl;
    private final IntSupplier commissionRateBp;

    /**
     * Неизменяемые настройки (значения из {@code application.yml} и тесты).
     *
     * @param maxUnpaid        сколько неоплаченных заказов («создан» и «ожидает оплаты») может быть у покупателя (FT-5.1)
     * @param reservationTtl   срок резерва ключей
     * @param sessionTtl       срок платёжной сессии
     * @param commissionRateBp ставка комиссии платформы на момент заказа, базисные пункты (200 это 2 %)
     */
    public OrderSettings(int maxUnpaid, Duration reservationTtl, Duration sessionTtl, int commissionRateBp) {
        if (maxUnpaid < 1) {
            throw new IllegalStateException("Лимит неоплаченных заказов " + MAX_UNPAID_KEY + " должен быть не меньше 1");
        }
        if (reservationTtl == null || reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalStateException("Срок резерва " + RESERVATION_TTL_KEY + " должен быть больше нуля");
        }
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalStateException("Срок платёжной сессии " + SESSION_TTL_KEY + " должен быть больше нуля");
        }
        if (sessionTtl.compareTo(reservationTtl) >= 0) {
            throw new IllegalStateException("INV-07: платёжная сессия (" + sessionTtl.toSeconds() + " с) должна быть короче резерва ("
                    + reservationTtl.toSeconds() + " с)");
        }
        if (commissionRateBp < 0 || commissionRateBp > 10_000) {
            throw new IllegalStateException("Ставка комиссии " + COMMISSION_KEY + " от 0 до 10000 базисных пунктов");
        }
        this.maxUnpaid = () -> maxUnpaid;
        this.reservationTtl = () -> reservationTtl;
        this.sessionTtl = () -> sessionTtl;
        this.commissionRateBp = () -> commissionRateBp;
    }

    private OrderSettings(ConfigValue maxUnpaid, ConfigValue reservationTtl, ConfigValue sessionTtl, ConfigValue commissionRateBp) {
        this.maxUnpaid = maxUnpaid::intValue;
        this.reservationTtl = reservationTtl::seconds;
        this.sessionTtl = sessionTtl::seconds;
        this.commissionRateBp = commissionRateBp::intValue;
    }

    /**
     * Живые настройки: регистрирует четыре ключа в реестре со значениями {@code initial} и границами сервиса и требует INV-07 для пары
     * сроков. Границы сервиса шире границ платформы: профиль {@code fast-time} задаёт секунды, которых платформа не допустила бы.
     */
    public static OrderSettings live(ConfigRegistry registry, OrderSettings initial) {
        ConfigValue maxUnpaid = registry.register(MAX_UNPAID_KEY, initial.maxUnpaid(), 1, 1000);
        ConfigValue reservationTtl = registry.register(RESERVATION_TTL_KEY, initial.reservationTtl().toSeconds(), 1, 86_400);
        ConfigValue sessionTtl = registry.register(SESSION_TTL_KEY, initial.sessionTtl().toSeconds(), 1, 86_400);
        ConfigValue commission = registry.register(COMMISSION_KEY, initial.commissionRateBp(), 0, 10_000);
        registry.requireShorter(SESSION_TTL_KEY, RESERVATION_TTL_KEY);
        return new OrderSettings(maxUnpaid, reservationTtl, sessionTtl, commission);
    }

    public int maxUnpaid() {
        return maxUnpaid.getAsInt();
    }

    public Duration reservationTtl() {
        return reservationTtl.get();
    }

    public Duration sessionTtl() {
        return sessionTtl.get();
    }

    public int commissionRateBp() {
        return commissionRateBp.getAsInt();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OrderSettings that && maxUnpaid() == that.maxUnpaid() && reservationTtl().equals(that.reservationTtl())
                && sessionTtl().equals(that.sessionTtl()) && commissionRateBp() == that.commissionRateBp();
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxUnpaid(), reservationTtl(), sessionTtl(), commissionRateBp());
    }

    @Override
    public String toString() {
        return "OrderSettings[maxUnpaid=" + maxUnpaid() + ", reservationTtl=" + reservationTtl() + ", sessionTtl=" + sessionTtl()
                + ", commissionRateBp=" + commissionRateBp() + "]";
    }
}
