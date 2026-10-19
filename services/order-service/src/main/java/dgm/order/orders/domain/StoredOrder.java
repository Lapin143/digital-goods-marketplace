package dgm.order.orders.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Заказ как он лежит в базе, со всем, что нужно переходам и событиям: покупатель, продавец, комиссия и адрес страницы оплаты. Отличается от
 * {@link Order} (вид для покупателя) тем, что ничего не скрывает. Необязательные поля в базе пусты до своего времени: сроки и адрес оплаты
 * до перехода «ожидает оплаты», причина отмены до отмены.
 */
public record StoredOrder(UUID id, long number, UUID buyerId, UUID sellerId, UUID productId, String productTitle, int quantity, long unitPrice,
                          long amount, long commission, String currency, String status, String cancelReason, String deliveryAddress,
                          Instant reserveUntil, Instant sessionUntil, String paymentSessionUrl, Instant createdAt, Instant paidAt,
                          Instant issuedAt) {

    /** Вид заказа для покупателя (без продавца, комиссии и адреса оплаты). */
    public Order view() {
        return new Order(id, number, productId, productTitle, quantity, unitPrice, amount, currency, status, Optional.ofNullable(cancelReason),
                "email", deliveryAddress, Optional.ofNullable(reserveUntil), Optional.ofNullable(sessionUntil), createdAt, Optional.ofNullable(paidAt),
                Optional.ofNullable(issuedAt));
    }
}
