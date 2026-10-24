package dgm.delivery.delivery.service;

import dgm.kit.time.Timestamps;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * События выдачи (тема {@code delivery.events}): имена и данные по AsyncAPI. Ни значений ключей, ни адреса, ни названия товара в них нет.
 * Ключ сообщения в Kafka это идентификатор заказа ({@code x-subject-field: orderId}).
 */
public final class DeliveryEvents {

    public static final String TOPIC = "delivery.events";
    public static final String AGGREGATE = "Delivery";
    public static final String ACCEPTED = "delivery.accepted";
    public static final String FAILED = "delivery.failed";
    public static final String DELIVERED = "delivery.delivered";
    public static final String OVERDUE = "delivery.overdue";
    public static final int SCHEMA_VERSION = 1;

    private DeliveryEvents() {
    }

    /** {@code delivery.accepted}: SM-06/T2. */
    public static Map<String, Object> accepted(UUID deliveryId, UUID orderId, String kind, int attemptNo, Instant acceptedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", deliveryId.toString());
        data.put("orderId", orderId.toString());
        data.put("deliveryType", kind);
        data.put("attemptNo", attemptNo);
        data.put("acceptedAt", Timestamps.format(acceptedAt));
        return data;
    }

    /** {@code delivery.failed}: SM-06/T4. */
    public static Map<String, Object> failed(UUID deliveryId, UUID orderId, String kind, String reason, int attempts) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", deliveryId.toString());
        data.put("orderId", orderId.toString());
        data.put("deliveryType", kind);
        data.put("reason", reason);
        data.put("attempts", attempts);
        return data;
    }

    /** {@code delivery.delivered}: SM-06/T5, время доставки по данным провайдера. */
    public static Map<String, Object> delivered(UUID deliveryId, UUID orderId, String kind, Instant deliveredAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", deliveryId.toString());
        data.put("orderId", orderId.toString());
        data.put("deliveryType", kind);
        data.put("deliveredAt", Timestamps.format(deliveredAt));
        return data;
    }

    /** {@code delivery.overdue}: контроль 30 минут (NFT-2.4), порог в секундах. */
    public static Map<String, Object> overdue(UUID deliveryId, UUID orderId, Instant paidAt, long thresholdSeconds) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", deliveryId.toString());
        data.put("orderId", orderId.toString());
        data.put("paidAt", Timestamps.format(paidAt));
        data.put("thresholdSeconds", thresholdSeconds);
        return data;
    }
}
