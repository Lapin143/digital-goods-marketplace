package dgm.order.orders.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Заказ глазами продавца (схема {@code SellerOrder}, FT-10.1, Р-189). Покупателя, его e-mail и адрес доставки здесь нет и быть не может (INV-44):
 * продавцу они не нужны, чтобы исполнить заказ, а читатель их даже не выбирает из базы. Цена, сумма и комиссия это снимок на момент заказа.
 */
public record SellerOrder(UUID id, long number, UUID productId, String productTitle, int quantity, long unitPrice, long amount, long commission,
                          String currency, String status, Optional<String> cancelReason, String issuanceMethod, Instant createdAt,
                          Optional<Instant> paidAt, Optional<Instant> issuedAt, Optional<Instant> refundedAt) {
}
