package dgm.payment.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Возврат, не прошедший автоматически и ожидающий ручного исполнения в кабинете шлюза (FT-6.4, схема {@code ManualRefund}).
 *
 * @param paymentId   платёж; возврат полный и единственный, поэтому его идентификатор равен идентификатору платежа
 * @param orderId     заказ
 * @param amount      сумма в копейках
 * @param currency    валюта
 * @param refundReason причина возврата
 * @param attempts    сколько автоматических попыток сделано
 * @param escalatedAt когда возврат попал в очередь
 */
public record ManualRefund(UUID paymentId, UUID orderId, long amount, String currency, String refundReason, int attempts, Instant escalatedAt) {
}
