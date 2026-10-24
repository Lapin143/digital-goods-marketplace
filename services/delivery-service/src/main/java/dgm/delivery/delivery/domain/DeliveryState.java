package dgm.delivery.delivery.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Состояние выдачи для применения статуса письма. Адреса и названия товара здесь нет: они персональные данные и в этих сценариях не нужны.
 *
 * @param id        идентификатор выдачи, он же идентификатор сообщения у провайдера
 * @param orderId   заказ
 * @param kind      {@code primary} или {@code repeat}
 * @param status    статус SM-06 ({@link DeliveryStatus})
 * @param attemptNo сколько попыток отправки выполнено
 */
public record DeliveryState(UUID id, UUID orderId, String kind, String status, int attemptNo) {

    public DeliveryState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
    }
}
