package dgm.order.orders.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Заказ покупателя в истории: цена и сумма это снимок на момент оформления (INV-12), значений ключей нет. Комиссия платформы
 * покупателю не показывается и в эту запись не входит.
 */
public record Order(UUID id, UUID productId, String productTitle, int quantity, long unitPrice, long amount, String currency,
                    String status, Optional<String> cancelReason, String deliveryChannel, String deliveryAddress,
                    Optional<Instant> reserveUntil, Optional<Instant> sessionUntil, Instant createdAt, Optional<Instant> paidAt,
                    Optional<Instant> issuedAt) {
}
