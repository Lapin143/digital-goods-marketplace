package dgm.payment.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Платёж с точки зрения возврата (SM-02/T4, INV-15): те же строка и права, что у {@link Payment}, плюс поля возврата. Возврат всегда
 * полный и единственный.
 *
 * @param id               идентификатор платежа
 * @param orderId          заказ
 * @param gatewayPaymentId внешний идентификатор платежа у шлюза
 * @param amount           сумма платежа в копейках, она же сумма возврата
 * @param currency         валюта
 * @param status           статус платежа ({@link Payment#CONFIRMED}, {@link Payment#REFUNDED} и другие)
 * @param refundReason     причина возврата или {@code null}, пока возврат не запрошен
 * @param gatewayRefundId  внешний идентификатор возврата у шлюза или {@code null}, пока шлюз его не назвал
 * @param inAdminQueue     автоматические попытки исчерпаны, возврат ждёт администратора (FT-6.4)
 * @param escalatedAt      когда возврат попал в очередь администратора или {@code null}
 * @param refundedAt       когда возврат подтверждён или {@code null}
 */
public record RefundPayment(UUID id, UUID orderId, String gatewayPaymentId, long amount, String currency, String status, String refundReason,
                            String gatewayRefundId, boolean inAdminQueue, Instant escalatedAt, Instant refundedAt) {

    /** Допустимые причины возврата: значения {@code ck_payment_refund_reason} и контракта события {@code order.refund-requested}. */
    public static final java.util.Set<String> REASONS = java.util.Set.of("late_payment", "seller_api_timeout", "dispute_decision");

    /** Платёж оплачен, деньги у платформы, возврат ещё не выполнен. */
    public boolean refundable() {
        return Payment.CONFIRMED.equals(status);
    }

    public boolean refunded() {
        return Payment.REFUNDED.equals(status);
    }

    /** Возврат уже запрошен: повторная команда второго возврата не создаёт. */
    public boolean refundRequested() {
        return refundReason != null;
    }
}
