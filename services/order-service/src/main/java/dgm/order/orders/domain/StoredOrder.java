package dgm.order.orders.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Заказ как он лежит в базе, со всем, что нужно переходам и событиям: покупатель, продавец, комиссия и адрес страницы оплаты. Отличается от
 * {@link Order} (вид для покупателя) тем, что ничего не скрывает. Необязательные поля в базе пусты до своего времени: сроки и адрес оплаты
 * до перехода «ожидает оплаты», причина отмены до отмены, время возврата до возврата.
 *
 * <p>{@code issuanceMethod} запоминается при оформлении и не меняется (триггер {@code trg_orders_immutable}): от него зависит, ждёт ли оплаченный заказ
 * ответа системы продавца и может ли сработать T10.
 */
public record StoredOrder(UUID id, long number, UUID buyerId, UUID sellerId, UUID productId, String productTitle, int quantity, long unitPrice,
                          long amount, long commission, String currency, String status, String cancelReason, String deliveryAddress,
                          Instant reserveUntil, Instant sessionUntil, String paymentSessionUrl, Instant createdAt, Instant paidAt,
                          Instant issuedAt, String issuanceMethod, Instant refundedAt) {

    /** Заказ товара по API продавца: ключ запрашивается у его системы после оплаты. */
    public boolean isSellerApi() {
        return IssuanceMethod.SELLER_API.equals(issuanceMethod);
    }

    /** Вид заказа для покупателя (без продавца, комиссии и адреса оплаты). */
    public Order view() {
        return new Order(id, number, productId, productTitle, quantity, unitPrice, amount, currency, status, Optional.ofNullable(cancelReason),
                "email", deliveryAddress, Optional.ofNullable(reserveUntil), Optional.ofNullable(sessionUntil), createdAt, Optional.ofNullable(paidAt),
                Optional.ofNullable(issuedAt));
    }
}
