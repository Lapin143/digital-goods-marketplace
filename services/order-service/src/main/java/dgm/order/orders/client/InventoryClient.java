package dgm.order.orders.client;

import dgm.kit.client.DependencyUnavailableException;
import java.time.Instant;
import java.util.UUID;

/**
 * Клиент остатков (адаптер, {@code inventory-client}): «зарезервировать ключи» (SEQ-01, шаги 6 – 8). Ключ идемпотентности выводится из имени
 * шага и идентификатора заказа ({@code IdempotencyKeys.derived("reserve", orderId)}, Р-149): повтор того же шага вернёт прежний резерв, а не
 * второй. Тайм-аут попытки 1 с, один повтор. Второй вызов, «подтвердить резерв» (шаг 5, ADR-013), выводит ключ из шага {@code confirm}.
 */
public interface InventoryClient {

    /**
     * @throws DependencyUnavailableException сервис остатков не ответил: резерв мог быть создан, заказ нужно отменить событием
     */
    ReserveResult reserve(UUID orderId, UUID productId, int quantity);

    /**
     * Шаг 5 саги: подтвердить резерв после оплаты. Одна атомарная операция остатков обслуживает обычную и позднюю оплату: активный резерв
     * закрепляется, снятый заменяется новым сразу в «использован», если ключей хватает на весь заказ (ADR-013).
     *
     * @throws DependencyUnavailableException сервис остатков не ответил: платёж остаётся подтверждённым, событие обрабатывается позже
     */
    ConfirmResult confirm(UUID orderId, UUID productId, int quantity);

    /** Исход подтверждения, который сервис остатков назвал сам. */
    sealed interface ConfirmResult permits Confirmed, NoKeys {
    }

    /** Ключи закреплены за заказом; {@code reReserved}: прежний резерв был снят и создан новый (поздняя оплата). */
    record Confirmed(UUID reservationId, boolean reReserved) implements ConfirmResult {
    }

    /** Ключей меньше заказанного или резерва заказа нет совсем: частичной выдачи нет (INV-05), деньги возвращаются. */
    record NoKeys(int available) implements ConfirmResult {
    }

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
