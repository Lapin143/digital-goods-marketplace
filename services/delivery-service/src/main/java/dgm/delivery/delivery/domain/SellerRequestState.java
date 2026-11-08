package dgm.delivery.delivery.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Состояние запроса для приёма ответа продавца: чей запрос, какой заказ, сколько ключей ждёт заказ, в каком состоянии запрос, срок
 * ответа и действующая аренда. Значений ключей и текста ответа продавца здесь нет.
 *
 * @param deliveryId  первичная выдача
 * @param requestId   идентификатор запроса
 * @param orderId     заказ
 * @param sellerId    продавец, которому принадлежит запрос
 * @param quantity    сколько ключей должно прийти
 * @param status      состояние ({@link SellerRequestStatus})
 * @param createdAt   когда запрос создан
 * @param deadlineAt  срок ответа
 * @param leaseUntil  до какого момента держится аренда на приём ответа, пусто, если её нет
 * @param errorCode   код отказа у {@code failed}
 */
public record SellerRequestState(UUID deliveryId, UUID requestId, UUID orderId, UUID sellerId, int quantity, String status, Instant createdAt,
                                 Instant deadlineAt, Instant leaseUntil, String errorCode) {

    public SellerRequestState {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
    }

    /** Аренда на приём ответа действует в момент {@code now}. */
    public boolean leased(Instant now) {
        return leaseUntil != null && leaseUntil.isAfter(now);
    }
}
