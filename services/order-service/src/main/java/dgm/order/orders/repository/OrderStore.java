package dgm.order.orders.repository;

import dgm.order.orders.domain.NewOrder;
import dgm.order.orders.domain.StoredOrder;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Запись заказов: единственный путь изменения таблицы {@code orders.orders}. Все методы вызываются внутри транзакции модуля
 * ({@code OrderTransaction}); вызов соседей внутри неё запрещён (правило 9).
 */
public interface OrderStore {

    /** Консультативная блокировка покупателя до конца транзакции: подсчёт неоплаченных заказов и вставка нового идут под ней (Р-142). */
    void lockBuyer(UUID buyerId);

    /** Сколько у покупателя заказов в статусах «создан» и «ожидает оплаты». */
    int countUnpaid(UUID buyerId);

    /** Вставляет заказ в статусе «создан» (SM-01/T1). Короткий номер выдаёт база. */
    void insert(NewOrder order);

    Optional<StoredOrder> find(UUID orderId);

    /** Заказ с блокировкой строки ({@code FOR UPDATE}): под ней проверяется статус и выполняется переход. */
    Optional<StoredOrder> lock(UUID orderId);

    /** «Создан» в «ожидает оплаты» (T2): срок резерва, срок сессии и адрес оплаты. {@code false}, если статус уже не «создан». */
    boolean awaitPayment(UUID orderId, Instant reserveUntil, Instant sessionUntil, String paymentSessionUrl);

    /** Отмена с причиной из статуса {@code from}. {@code false}, если статус заказа уже другой. */
    boolean cancel(UUID orderId, String from, String reason);
}
