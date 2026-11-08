package dgm.platform.notification.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Новое уведомление продавцу о событии заказа (Р-193): получатель, событие и тело. Тело собирает обработчик из события, персональных данных
 * покупателя в нём нет (INV-44); база тоже проверяет это ограничением {@code ck_webhook_delivery_payload}.
 *
 * @param id          идентификатор доставки ({@code deliveryId} в списке кабинета)
 * @param sellerId    продавец-получатель
 * @param eventId     идентификатор события заказа: одно событие даёт одно уведомление, он же заголовок {@code X-Event-Id} всех попыток
 * @param eventType   {@code order.paid}, {@code order.issued}, {@code order.cancelled} или {@code order.refunded}
 * @param orderNumber короткий номер заказа
 * @param payload     тело уведомления
 * @param createdAt   когда уведомление поставлено в очередь, оно же срок первой попытки
 */
public record NewWebhookDelivery(UUID id, UUID sellerId, UUID eventId, String eventType, long orderNumber, Map<String, Object> payload,
                                 Instant createdAt) {

    public NewWebhookDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(createdAt, "createdAt");
        payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
    }
}
