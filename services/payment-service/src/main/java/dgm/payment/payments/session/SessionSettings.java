package dgm.payment.payments.session;

import dgm.kit.config.ConfigRegistry;
import dgm.kit.config.ConfigValue;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Сроки и адреса платёжной сессии. Ключи совпадают с параметрами платформы (Р-139): {@code reservation.ttl-seconds},
 * {@code payment-session.ttl-seconds}; сроки читаются из настроек, а не из чисел в коде (правило 14 руководства среза 2).
 * {@link #live} подключает оба срока к реестру параметров: каждое обращение читает текущее значение, и смена параметра на платформе
 * действует на новые платёжные сессии без перезапуска (правило 19 руководства среза 3).
 *
 * <p>INV-07: платёжная сессия короче резерва ключей не меньше чем на {@code margin}. Нарушение значений из настроек останавливает запуск
 * сервиса: иначе покупатель мог бы оплатить заказ, резерв которого уже снят, а такая оплата это поздняя оплата с возвратом (ADR-013),
 * а не штатный ход. Сообщения платформы с нарушением слушатель не применяет.
 */
public final class SessionSettings {

    public static final String RESERVATION_TTL_KEY = "reservation.ttl-seconds";
    public static final String SESSION_TTL_KEY = "payment-session.ttl-seconds";
    public static final String MARGIN_KEY = "payment-session.margin-seconds";
    public static final String PUBLIC_ORIGIN_KEY = "dgm.payment.public-origin";

    private final Supplier<Duration> reservationTtl;
    private final Supplier<Duration> sessionTtl;
    private final Duration margin;
    private final String publicOrigin;

    /**
     * Неизменяемые настройки (значения из {@code application.yml} и тесты).
     *
     * @param reservationTtl срок резерва ключей
     * @param sessionTtl     срок платёжной сессии
     * @param margin         наименьший запас между концом сессии и концом резерва
     * @param publicOrigin   публичный адрес платформы без завершающей косой черты (Р-136): основа адреса возврата покупателя
     */
    public SessionSettings(Duration reservationTtl, Duration sessionTtl, Duration margin, String publicOrigin) {
        if (reservationTtl == null || reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalStateException("Срок резерва " + RESERVATION_TTL_KEY + " должен быть больше нуля");
        }
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalStateException("Срок платёжной сессии " + SESSION_TTL_KEY + " должен быть больше нуля");
        }
        if (margin == null || margin.isNegative()) {
            throw new IllegalStateException("Запас " + MARGIN_KEY + " не может быть отрицательным");
        }
        // сессия строго короче резерва даже при нулевом запасе: равные сроки оставляют покупателю оплату в ту секунду, когда резерв снимается
        if (sessionTtl.compareTo(reservationTtl) >= 0 || sessionTtl.plus(margin).compareTo(reservationTtl) > 0) {
            throw new IllegalStateException("INV-07: платёжная сессия (" + sessionTtl.toSeconds() + " с) должна быть короче резерва ("
                    + reservationTtl.toSeconds() + " с) не меньше чем на " + margin.toSeconds() + " с");
        }
        if (publicOrigin == null || !publicOrigin.matches("^https?://[^/\\s]+$")) {
            throw new IllegalStateException("Публичный адрес " + PUBLIC_ORIGIN_KEY + " должен быть вида https://хост[:порт] без пути");
        }
        this.reservationTtl = () -> reservationTtl;
        this.sessionTtl = () -> sessionTtl;
        this.margin = margin;
        this.publicOrigin = publicOrigin;
    }

    private SessionSettings(ConfigValue reservationTtl, ConfigValue sessionTtl, Duration margin, String publicOrigin) {
        this.reservationTtl = reservationTtl::seconds;
        this.sessionTtl = sessionTtl::seconds;
        this.margin = margin;
        this.publicOrigin = publicOrigin;
    }

    /**
     * Живые настройки: регистрирует оба срока в реестре со значениями {@code initial} и границами сервиса и требует INV-07 с запасом.
     * Запас и публичный адрес не параметры платформы и остаются значениями из настроек.
     */
    public static SessionSettings live(ConfigRegistry registry, SessionSettings initial) {
        ConfigValue reservationTtl = registry.obtain(RESERVATION_TTL_KEY, initial.reservationTtl().toSeconds(), 1, 86_400);
        ConfigValue sessionTtl = registry.obtain(SESSION_TTL_KEY, initial.sessionTtl().toSeconds(), 1, 86_400);
        registry.requireShorter(SESSION_TTL_KEY, RESERVATION_TTL_KEY, initial.margin().toSeconds());
        return new SessionSettings(reservationTtl, sessionTtl, initial.margin(), initial.publicOrigin());
    }

    /** Срок резерва ключей. */
    public Duration reservationTtl() {
        return reservationTtl.get();
    }

    /** Срок платёжной сессии. */
    public Duration sessionTtl() {
        return sessionTtl.get();
    }

    /** Наименьший запас между концом сессии и концом резерва. */
    public Duration margin() {
        return margin;
    }

    /** Публичный адрес платформы без завершающей косой черты. */
    public String publicOrigin() {
        return publicOrigin;
    }

    /** Страница заказа, на которую шлюз возвращает покупателя. Она ничего не решает: решение о деньгах принимает вебхук (ADR-015). */
    public String returnUrl(UUID orderId) {
        return publicOrigin + "/orders/" + orderId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SessionSettings that && reservationTtl().equals(that.reservationTtl()) && sessionTtl().equals(that.sessionTtl())
                && margin.equals(that.margin) && publicOrigin.equals(that.publicOrigin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reservationTtl(), sessionTtl(), margin, publicOrigin);
    }

    @Override
    public String toString() {
        return "SessionSettings[reservationTtl=" + reservationTtl() + ", sessionTtl=" + sessionTtl() + ", margin=" + margin + ", publicOrigin="
                + publicOrigin + "]";
    }
}
