package dgm.catalog.sellers.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Строка очереди заявок модератора (схема {@code SellerApplicationQueueItem}).
 *
 * @param documentsCount число документов заявки
 */
public record ReviewQueueItem(UUID sellerId, String name, SellerType sellerType, int documentsCount, Instant submittedAt,
                              Instant reviewDeadlineAt) {

    /** Срок рассмотрения вышел: заявка в очереди, поэтому достаточно сравнить срок с текущим моментом. */
    public boolean overdue(Instant now) {
        return !now.isBefore(reviewDeadlineAt);
    }
}
