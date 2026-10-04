package dgm.kit.outbox;

import java.util.UUID;

/**
 * Сообщение к отправке в Kafka: ключ, значение (конверт CloudEvents) и два заголовка записи.
 *
 * @param topic       тема
 * @param key         ключ записи (идентификатор агрегата)
 * @param value       конверт события в виде JSON-текста
 * @param type        значение заголовка {@code ce_type}
 * @param traceparent значение заголовка {@code traceparent}
 * @param eventId     идентификатор события, для журнала и сообщений об ошибке
 */
public record OutgoingEvent(String topic, String key, String value, String type, String traceparent, UUID eventId) {
}
