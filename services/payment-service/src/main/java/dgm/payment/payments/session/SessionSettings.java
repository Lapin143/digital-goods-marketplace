package dgm.payment.payments.session;

import java.time.Duration;
import java.util.UUID;

/**
 * Сроки и адреса платёжной сессии. Ключи совпадают с параметрами платформы (Р-139): {@code reservation.ttl-seconds},
 * {@code payment-session.ttl-seconds}; сроки читаются из настроек, а не из чисел в коде (правило 14 руководства среза 2).
 *
 * <p>INV-07: платёжная сессия короче резерва ключей не меньше чем на {@code margin}. Нарушение останавливает запуск сервиса: иначе
 * покупатель мог бы оплатить заказ, резерв которого уже снят, а такая оплата это поздняя оплата с возвратом (ADR-013), а не штатный ход.
 *
 * @param reservationTtl срок резерва ключей
 * @param sessionTtl     срок платёжной сессии
 * @param margin         наименьший запас между концом сессии и концом резерва
 * @param publicOrigin   публичный адрес платформы без завершающей косой черты (Р-136): основа адреса возврата покупателя
 */
public record SessionSettings(Duration reservationTtl, Duration sessionTtl, Duration margin, String publicOrigin) {

    public static final String RESERVATION_TTL_KEY = "reservation.ttl-seconds";
    public static final String SESSION_TTL_KEY = "payment-session.ttl-seconds";
    public static final String MARGIN_KEY = "payment-session.margin-seconds";
    public static final String PUBLIC_ORIGIN_KEY = "dgm.payment.public-origin";

    public SessionSettings {
        if (reservationTtl == null || reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalStateException("Срок резерва " + RESERVATION_TTL_KEY + " должен быть больше нуля");
        }
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalStateException("Срок платёжной сессии " + SESSION_TTL_KEY + " должен быть больше нуля");
        }
        if (margin == null || margin.isNegative()) {
            throw new IllegalStateException("Запас " + MARGIN_KEY + " не может быть отрицательным");
        }
        if (sessionTtl.plus(margin).compareTo(reservationTtl) > 0) {
            throw new IllegalStateException("INV-07: платёжная сессия (" + sessionTtl.toSeconds() + " с) должна быть короче резерва ("
                    + reservationTtl.toSeconds() + " с) не меньше чем на " + margin.toSeconds() + " с");
        }
        if (publicOrigin == null || !publicOrigin.matches("^https?://[^/\\s]+$")) {
            throw new IllegalStateException("Публичный адрес " + PUBLIC_ORIGIN_KEY + " должен быть вида https://хост[:порт] без пути");
        }
    }

    /** Страница заказа, на которую шлюз возвращает покупателя. Она ничего не решает: решение о деньгах принимает вебхук (ADR-015). */
    public String returnUrl(UUID orderId) {
        return publicOrigin + "/orders/" + orderId;
    }
}
