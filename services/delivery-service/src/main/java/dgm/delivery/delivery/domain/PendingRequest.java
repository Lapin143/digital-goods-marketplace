package dgm.delivery.delivery.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Запрос продавцу, взятый отправителем в аренду. {@code attempts} это сколько раз запрос уже отправлялся (от 0 до 2): следующая отправка
 * имеет номер {@code attempts + 1}. Названия товара и адреса здесь нет.
 *
 * @param deliveryId первичная выдача заказа, ключ запроса
 * @param requestId  идентификатор запроса, его продавец возвращает в ответе
 * @param orderId    заказ
 * @param sellerId   продавец
 * @param productId  товар
 * @param orderNumber короткий номер заказа
 * @param quantity   сколько ключей ждёт заказ
 * @param createdAt  когда запрос создан
 * @param deadlineAt срок ответа
 * @param attempts   сколько отправок уже было
 */
public record PendingRequest(UUID deliveryId, UUID requestId, UUID orderId, UUID sellerId, UUID productId, long orderNumber, int quantity, Instant createdAt,
                             Instant deadlineAt, int attempts) {

    public PendingRequest {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (quantity < 1) {
            throw new IllegalArgumentException("В запросе хотя бы один ключ");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("Число отправок не бывает отрицательным");
        }
    }

    /** Номер отправки, которую нужно выполнить. */
    public int nextAttemptNo() {
        return attempts + 1;
    }

    /** Дополняет запрос данными выдачи: заказом и числом ключей (в таблице запросов их нет). */
    public PendingRequest withDelivery(UUID order, int count) {
        return new PendingRequest(deliveryId, requestId, order, sellerId, productId, orderNumber, count, createdAt, deadlineAt, attempts);
    }
}
