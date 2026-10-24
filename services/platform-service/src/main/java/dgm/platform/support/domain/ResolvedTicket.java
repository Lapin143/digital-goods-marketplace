package dgm.platform.support.domain;

import java.util.UUID;

/**
 * Обращение, закрытое доставкой (SM-07/T4).
 *
 * @param id      обращение
 * @param buyerId покупатель заказа
 */
public record ResolvedTicket(UUID id, UUID buyerId) {
}
