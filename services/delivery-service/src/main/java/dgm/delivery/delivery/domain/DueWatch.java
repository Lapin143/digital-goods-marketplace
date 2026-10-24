package dgm.delivery.delivery.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Контроль доставки, срок которого наступил и который ещё открыт (NFT-2.4).
 *
 * @param orderId    заказ
 * @param deadlineAt срок: момент оплаты плюс окно контроля
 */
public record DueWatch(UUID orderId, Instant deadlineAt) {

    public DueWatch {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
    }
}
