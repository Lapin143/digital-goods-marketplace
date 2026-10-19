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
final class DeliveryEvents {

    static final String TOPIC = "delivery.events";
    static final String AGGREGATE = "Delivery";
    static final String ACCEPTED = "delivery.accepted";
    static final String FAILED = "delivery.failed";
    static final int SCHEMA_VERSION = 1;

    private DeliveryEvents() {
    }

    /** {@code delivery.accepted}: SM-06/T2. */
    static Map<String, Object> accepted(UUID deliveryId, UUID orderId, String kind, int attemptNo, Instant acceptedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", deliveryId.toString());
        data.put("orderId", orderId.toString());
        data.put("deliveryType", kind);
        data.put("attemptNo", attemptNo);
        data.put("acceptedAt", Timestamps.format(acceptedAt));
        return data;
    }

    /** {@code delivery.failed}: SM-06/T4. */
    static Map<String, Object> failed(UUID deliveryId, UUID orderId, String kind, String reason, int attempts) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", deliveryId.toString());
        data.put("orderId", orderId.toString());
        data.put("deliveryType", kind);
        data.put("reason", reason);
        data.put("attempts", attempts);
        return data;
    }
}
