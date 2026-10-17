package dgm.catalog.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/** Строка очереди товаров у модератора (схема {@code ModerationQueueItem}). Цена в копейках. */
public record ModerationQueueItem(UUID productId, UUID sellerId, String title, String productType, long price, String currency,
                                  Instant submittedAt, Instant reviewDeadlineAt) {

    /** Срок рассмотрения вышел: товар в очереди, поэтому достаточно сравнить срок с текущим моментом. */
    public boolean overdue(Instant now) {
        return !now.isBefore(reviewDeadlineAt);
    }
}
