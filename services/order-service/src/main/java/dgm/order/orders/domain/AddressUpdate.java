package dgm.order.orders.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Итог обновления снимка адреса доставки ({@code updateDeliveryAddress}, OP-72): адрес записан в заказ, момент обновления возвращается вызывающему.
 * Адрес это персональные данные: в журнал, метрики и сообщения об ошибках запись не попадает, {@link #toString()} адрес скрывает.
 */
public record AddressUpdate(UUID orderId, String address, Instant updatedAt) {

    @Override
    public String toString() {
        return "AddressUpdate[orderId=" + orderId + ", updatedAt=" + updatedAt + "]";
    }
}
