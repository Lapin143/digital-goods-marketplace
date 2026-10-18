package dgm.payment.payments.gateway;

import java.time.Instant;

/**
 * Платёж у шлюза в модели платформы.
 *
 * @param id         внешний идентификатор платежа, непрозрачная строка
 * @param payUrl     адрес страницы оплаты у шлюза
 * @param expiresAt  срок сессии по часам шлюза или {@code null}, если шлюз его не сообщил
 */
public record GatewayPayment(String id, String payUrl, Instant expiresAt) {
}
