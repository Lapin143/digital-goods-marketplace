package dgm.platform.notification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Строка списка доставок продавца ({@code WebhookDelivery}, OP-102): тип, номер заказа, статус, число попыток, последний код ответа и время следующей
 * попытки. Тела и подписи в ней нет.
 *
 * @param lastResponseCode  код последнего ответа получателя, {@code null}, если ответа не было
 * @param lastAttemptAt     когда была последняя попытка, {@code null}, если попыток не было
 * @param nextAttemptAt     срок следующей попытки, есть только у уведомления в очереди
 */
public record WebhookDelivery(UUID id, UUID eventId, String eventType, long orderNumber, String status, int attempts, Integer lastResponseCode,
                              Instant lastAttemptAt, Instant nextAttemptAt, Instant createdAt) {
}
