package dgm.platform.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Что просит продавец в списке доставок: свои уведомления, необязательный отбор по статусам и страница после курсора. Продавец берётся из токена
 * (U3), другого в запросе задать нельзя.
 *
 * @param statuses отбор по статусам, пусто: все
 * @param limit    размер страницы, от 1 до 100
 * @param after    курсор: пара «время постановки, идентификатор» последней строки предыдущей страницы
 */
public record WebhookDeliveryFilter(UUID sellerId, Optional<List<String>> statuses, int limit, Optional<After> after) {

    /** Позиция в списке: новые выше, порядок {@code (created_at, id)} по убыванию. */
    public record After(Instant createdAt, UUID id) {
    }
}
