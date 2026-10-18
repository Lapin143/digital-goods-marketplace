package dgm.payment.payments.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Уведомления шлюза, которые не удалось сопоставить с платежом ({@code payments.unmatched_notification}, ADR-015, шаг 3). Тело
 * уведомления не хранится: по идентификаторам планировщик находит платёж, когда он появится, и применяет уведомление заново.
 */
public interface UnmatchedNotificationRepository {

    /** Запись о несопоставленном уведомлении. */
    record Unmatched(UUID id, String gatewayPaymentId, String eventType, String gatewayEventId, UUID orderId, Instant occurredAt, Instant receivedAt) {
    }

    /** @return {@code true}, если запись сделана этим вызовом; повтор той же пары не создаёт второй записи */
    boolean insertIfAbsent(Unmatched notification);

    /** Несопоставленные уведомления, старые первыми. */
    List<Unmatched> pending(int limit);

    /** Уведомление применено: больше не ждёт. */
    void markMatched(UUID id, Instant at);

    /** Несопоставленные дольше срока и без отправленного оповещения. */
    List<Unmatched> overdueWithoutAlert(Instant receivedBefore, int limit);

    void markAlerted(UUID id, Instant at);
}
