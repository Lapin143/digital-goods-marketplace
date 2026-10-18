package dgm.inventory.inventory.reservation.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Резерв ключей за заказом (SM-08, INV-05, INV-06): временное закрепление всего заказанного количества, частичного резерва нет.
 *
 * @param id            идентификатор резерва
 * @param orderId       заказ (у заказа за жизнь несколько резервов, активный не больше одного)
 * @param productId     товар
 * @param quantity      сколько ключей закреплено, от 1 до 10
 * @param status        {@code active}, {@code used} или {@code released}
 * @param expiresAt     срок: создание плюс {@code reservation.ttl-seconds}
 * @param releaseReason причина снятия у снятого резерва ({@code expired}, {@code payment_declined}, {@code order_cancelled}), иначе пусто
 */
public record Reservation(UUID id, UUID orderId, UUID productId, int quantity, String status, Instant expiresAt, String releaseReason) {

    public static final String ACTIVE = "active";
    public static final String USED = "used";
    public static final String RELEASED = "released";

    public static final String EXPIRED = "expired";
    public static final String PAYMENT_DECLINED = "payment_declined";
    public static final String ORDER_CANCELLED = "order_cancelled";

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    public boolean isUsed() {
        return USED.equals(status);
    }

    public boolean isReleased() {
        return RELEASED.equals(status);
    }
}
