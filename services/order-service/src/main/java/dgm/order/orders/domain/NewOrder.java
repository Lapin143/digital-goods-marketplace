package dgm.order.orders.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Заказ в момент создания (SM-01/T1): снимки товара, цены, комиссии и адреса доставки. Сумма и комиссия посчитаны заранее
 * ({@link OrderPricing}), база их перепроверяет ограничениями INV-12 и INV-13.
 *
 * @param commissionRateBp ставка комиссии на момент заказа, базисные пункты
 * @param deliveryAddress  снимок e-mail покупателя (персональные данные, INV-44)
 */
public record NewOrder(UUID id, UUID buyerId, UUID sellerId, UUID productId, String productTitle, int quantity, long unitPrice, long amount,
                       int commissionRateBp, long commission, String currency, String deliveryAddress, Instant createdAt) {
}
