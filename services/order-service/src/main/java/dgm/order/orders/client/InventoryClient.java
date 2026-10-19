package dgm.order.orders.client;

import dgm.kit.client.DependencyUnavailableException;
import java.time.Instant;
import java.util.UUID;

/**
 * Клиент остатков (адаптер, {@code inventory-client}): «зарезервировать ключи» (SEQ-01, шаги 6 – 8). Ключ идемпотентности выводится из имени
 * шага и идентификатора заказа ({@code IdempotencyKeys.derived("reserve", orderId)}, Р-149): повтор того же шага вернёт прежний резерв, а не
 * второй. Тайм-аут попытки 1 с, один повтор.
 */
public interface InventoryClient {

    /**
     * @throws DependencyUnavailableException сервис остатков не ответил: резерв мог быть создан, заказ нужно отменить событием
     */
    ReserveResult reserve(UUID orderId, UUID productId, int quantity);

    /** Исход вызова, который сервис остатков назвал сам. */
    sealed interface ReserveResult permits Reserved, OutOfStock, UnknownProduct {
    }

    /** Резерв создан (или найден прежний с тем же ключом). */
    record Reserved(UUID reservationId, Instant expiresAt) implements ReserveResult {
    }

    /** Свободных ключей меньше, чем заказано; ничего не зарезервировано. */
    record OutOfStock(int available) implements ReserveResult {
    }

    /** Сервис остатков не знает товара (копия товара ещё не дошла из каталога) или товар у него не опубликован; ничего не зарезервировано. */
    record UnknownProduct() implements ReserveResult {
    }
}
