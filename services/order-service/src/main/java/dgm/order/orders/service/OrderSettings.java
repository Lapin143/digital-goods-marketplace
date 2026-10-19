package dgm.order.orders.service;

import java.time.Duration;

/**
 * Настройки заказа. Ключи совпадают с параметрами платформы (Р-139); до слушателя {@code config.changed} (срез 3) значения читаются из
 * {@code application.yml}. Сроки не зашиты в код (правило 14 руководства среза 2).
 *
 * <p>INV-07: платёжная сессия строго короче резерва ключей. Нарушение останавливает запуск: покупатель мог бы оплатить заказ, резерв которого
 * уже снят (поздняя оплата с возвратом, ADR-013).
 *
 * @param maxUnpaid        сколько неоплаченных заказов («создан» и «ожидает оплаты») может быть у покупателя (FT-5.1)
 * @param reservationTtl   срок резерва ключей
 * @param sessionTtl       срок платёжной сессии
 * @param commissionRateBp ставка комиссии платформы на момент заказа, базисные пункты (200 это 2 %)
 */
public record OrderSettings(int maxUnpaid, Duration reservationTtl, Duration sessionTtl, int commissionRateBp) {

    public static final String MAX_UNPAID_KEY = "orders.max-unpaid";
    public static final String RESERVATION_TTL_KEY = "reservation.ttl-seconds";
    public static final String SESSION_TTL_KEY = "payment-session.ttl-seconds";
    public static final String COMMISSION_KEY = "commission.default-rate-bp";

    public OrderSettings {
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
    }
}
