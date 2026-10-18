package dgm.inventory.inventory.reservation.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Очередь сроков резервов (ADR-012, Р-141): быстрый способ узнать, у каких резервов срок наступил, без опроса таблицы каждую секунду.
 * Источник истины это база ({@code reservation.expires_at}): очередь может потерять записи (перезапуск Redis, недоступность в момент
 * создания резерва), и тогда резерв снимает сверка по базе. Поэтому операции очереди не должны ломать бизнес-действие: вызывающий
 * ловит исключение и считает его в метрике.
 */
public interface ExpiryQueue {

    /** Ставит или обновляет срок резерва. */
    void schedule(UUID reservationId, Instant expiresAt);

    /** Резервы, срок которых наступил к моменту {@code now}, по возрастанию срока, не больше {@code limit}. Из очереди не удаляет. */
    List<UUID> due(Instant now, int limit);

    /** Убирает резерв из очереди: он снят или использован. */
    void remove(UUID reservationId);
}
