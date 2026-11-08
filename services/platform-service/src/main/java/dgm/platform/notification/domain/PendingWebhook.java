package dgm.platform.notification.domain;

import java.util.UUID;

/**
 * Уведомление, взятое в аренду для отправки. Тело это текст JSON из базы: повтор шлёт ровно те же байты, а подписывается каждая попытка заново (метка
 * времени у неё своя).
 *
 * @param attempts сколько попыток уже сделано, от 0 до 5
 */
public record PendingWebhook(UUID id, UUID sellerId, UUID eventId, String eventType, String payload, int attempts) {

    /** Номер попытки, которая делается сейчас, от 1. */
    public int nextAttemptNo() {
        return attempts + 1;
    }
}
