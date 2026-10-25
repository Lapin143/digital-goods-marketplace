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

    /**
     * Оплата из статуса {@code from}: «ожидает оплаты» (T4) или «отменён» (T7, поздняя оплата). Причина отмены очищается: заказ больше не
     * отменён. {@code false}, если статус заказа уже другой.
     */
    boolean pay(UUID orderId, String from, Instant paidAt);

    /** «Отменён» в «возвращён» (T8). {@code false}, если статус заказа уже другой. */
    boolean refund(UUID orderId);

    /** «Оплачен» в «выдан» (T9), момент первой выдачи фиксируется один раз (INV-17). {@code false}, если статус заказа уже другой. */
    boolean issue(UUID orderId, Instant issuedAt);

    /** Заменяет снимок адреса доставки во всех заказах покупателя (INV-44); возвращает число изменённых заказов. */
    int anonymizeAddress(UUID buyerId, String replacement);

    /**
     * Заменяет снимок адреса доставки одного заказа «выдан» (OP-72) и пишет строку всегда, даже если адрес тот же: версия и время изменения растут,
     * это и есть запись «адрес актуален на момент решения оператора» (SEQ-04). Статус и сроки не затрагиваются. {@code false}, если заказ уже не «выдан».
     */
    boolean replaceAddress(UUID orderId, String address);
}
