package dgm.kit.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Строка таблицы {@code outbox}, ожидающая отправки.
 *
 * @param id            порядковый номер
 * @param eventId       идентификатор события
 * @param aggregateType тип агрегата
 * @param aggregateId   идентификатор агрегата (ключ записи Kafka)
 * @param eventType     тип события
 * @param topic         тема
 * @param payload       тело события, JSON-текст как его хранит PostgreSQL
 * @param headers       заголовки события, JSON-текст
 * @param createdAt     время записи
 * @param attempts      число прежних неудачных попыток
 */
public record OutboxRow(long id, UUID eventId, String aggregateType, String aggregateId, String eventType, String topic,
                        String payload, String headers, Instant createdAt, int attempts) {
}
