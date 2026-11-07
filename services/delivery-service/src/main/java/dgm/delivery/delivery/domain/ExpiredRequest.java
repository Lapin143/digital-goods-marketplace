package dgm.delivery.delivery.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Запрос без ответа, срок которого вышел и на который не действует аренда: исполнитель тайм-аута блокирует его в своей транзакции.
 *
 * @param deliveryId первичная выдача
 * @param requestId  идентификатор запроса
 * @param orderId    заказ
 * @param sellerId   продавец
 * @param createdAt  когда запрос создан: от него считается, сколько платформа ждала
 */
public record ExpiredRequest(UUID deliveryId, UUID requestId, UUID orderId, UUID sellerId, Instant createdAt) {

    public ExpiredRequest {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
