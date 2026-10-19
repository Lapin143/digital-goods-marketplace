package dgm.order.orders.service;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.order.orders.domain.CancelReason;
import dgm.order.orders.domain.NewOrder;
import dgm.order.orders.domain.OrderStateMachine;
import dgm.order.orders.domain.OrderStateMachine.Transition;
import dgm.order.orders.domain.OrderStatus;
import dgm.order.orders.domain.StoredOrder;
import dgm.order.orders.repository.OrderStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Домен заказа: единственное место, где меняется статус (c4-components-order-service.md, {@code order-domain}). Каждый переход выполняется
 * одной короткой транзакцией: блокировка строки, проверка статуса по таблице {@link OrderStateMachine}, изменение и запись события в Outbox.
 * Соседей этот класс не вызывает: их вызывает {@link SagaOrchestrator} между переходами и вне транзакций (правило 9).
 *
 * <p>Повторный и запоздавший вызов безвреден: если заказ уже не в нужном статусе, переход не выполняется и возвращается пусто.
 */
public final class OrderTransitions {

    private static final Logger LOG = LoggerFactory.getLogger(OrderTransitions.class);

    /**
     * Что остаётся в снимке адреса после обезличивания (INV-44). Зона {@code .invalid} зарезервирована (RFC 2606): письмо на такой адрес не уйдёт
     * никогда, а длина и вид проходят ограничения базы и показ адреса в истории.
     */
    public static final String ANONYMIZED_ADDRESS = "anonymized@anonymized.invalid";

    private final OrderStore store;
    private final OrderTransaction transaction;
    private final OrderSettings settings;
    private final MeterRegistry meters;

    public OrderTransitions(OrderStore store, OrderTransaction transaction, OrderSettings settings, MeterRegistry meters) {
        this.store = store;
        this.transaction = transaction;
        this.settings = settings;
        this.meters = meters;
    }

    /**
     * T1: заказ создан. Подсчёт неоплаченных заказов и вставка идут под консультативной блокировкой покупателя, поэтому два параллельных запроса
     * не увидят одно и то же число и не создадут лишний заказ (Р-142).
     *
     * @throws ProblemException 409 {@code unpaid-orders-limit}, если у покупателя уже {@code orders.max-unpaid} неоплаченных заказов
     */
    public StoredOrder place(NewOrder draft) {
        return transaction.execute(() -> {
            store.lockBuyer(draft.buyerId());
            int unpaid = store.countUnpaid(draft.buyerId());
            if (unpaid >= settings.maxUnpaid()) {
                count("orders_rejected_total", "reason", "unpaid-orders-limit");
                throw new ProblemException(ProblemType.UNPAID_ORDERS_LIMIT,
                        "У вас уже " + unpaid + " неоплаченных заказа: оплатите их или дождитесь окончания срока оплаты.",
                        Map.of("limit", settings.maxUnpaid()), Map.of());
            }
            store.insert(draft);
            count("orders_created_total", "stage", "placed");
            return store.find(draft.id()).orElseThrow(() -> new IllegalStateException("Заказ не найден после вставки"));
        });
    }

    /**
     * T2: «создан» в «ожидает оплаты»: резерв создан, платёжная сессия открыта. Событие {@code order.created} уходит в Outbox той же транзакцией.
     *
     * @return заказ или пусто, если статус уже не «создан» (заказ успели отменить, например сторожем)
     */
    public Optional<StoredOrder> awaitPayment(UUID orderId, Instant reserveUntil, Instant sessionUntil, String paymentSessionUrl) {
        return transaction.execute(() -> {
            StoredOrder order = store.lock(orderId).orElseThrow(() -> new IllegalStateException("Заказ " + orderId + " не найден"));
            Optional<Transition> transition = OrderStateMachine.find(order.status(), OrderStatus.AWAITING_PAYMENT, null);
            if (transition.isEmpty() || !store.awaitPayment(orderId, reserveUntil, sessionUntil, paymentSessionUrl)) {
                LOG.warn("Заказ {} не перешёл в «ожидает оплаты»: статус {}", orderId, order.status());
                return Optional.<StoredOrder>empty();
            }
            StoredOrder updated = store.find(orderId).orElseThrow(() -> new IllegalStateException("Заказ пропал после перехода"));
            transaction.publish(OrderEvents.TOPIC, OrderEvents.AGGREGATE, orderId.toString(), OrderEvents.CREATED, OrderEvents.SCHEMA_VERSION,
                    OrderEvents.created(updated));
            count("orders_created_total", "stage", "awaiting_payment");
            return Optional.of(updated);
        });
    }

    /**
     * Отмена (T3, T5, T6, T12): причина выбирает переход. Событие {@code order.cancelled} уходит в Outbox той же транзакцией, по нему
     * {@code inventory-service} снимает резерв. Пока заказ «создан» и резерва мог не быть, событие всё равно издаётся: потребитель его не найдёт и
     * ничего не сделает, а если ответ о резерве потерян, резерв будет снят.
     *
     * @return заказ после отмены или пусто, если заказ уже отменён, оплачен или не найден
     */
    public Optional<StoredOrder> cancel(UUID orderId, String reason) {
        return transaction.execute(() -> {
            StoredOrder order = store.lock(orderId).orElse(null);
            return order == null ? Optional.<StoredOrder>empty() : cancelLocked(order, reason);
        });
    }

    private Optional<StoredOrder> cancelLocked(StoredOrder order, String reason) {
        UUID orderId = order.id();
        Optional<Transition> transition = OrderStateMachine.find(order.status(), OrderStatus.CANCELLED, reason);
        if (transition.isEmpty() || !store.cancel(orderId, order.status(), reason)) {
            LOG.info("Заказ {} не отменён: статус {}, причина {}", orderId, order.status(), reason);
            return Optional.<StoredOrder>empty();
        }
        StoredOrder updated = store.find(orderId).orElseThrow(() -> new IllegalStateException("Заказ пропал после отмены"));
        transaction.publish(OrderEvents.TOPIC, OrderEvents.AGGREGATE, orderId.toString(), OrderEvents.CANCELLED, OrderEvents.SCHEMA_VERSION,
                OrderEvents.cancelled(updated, reason));
        meters.counter("orders_cancelled_total", "reason", reason, "transition", transition.get().id()).increment();
        return Optional.of(updated);
    }

    /** Заказ как он лежит в базе, короткой транзакцией: по статусу оркестратор решает, нужен ли вызов соседа (c4-components-order-service, 3.1). */
    public Optional<StoredOrder> find(UUID orderId) {
        return transaction.execute(() -> store.find(orderId));
    }

    /**
     * T4 и T7: платёж подтверждён и резерв закреплён. Заказ «ожидает оплаты» (T4) или «отменён» (T7, поздняя оплата, ADR-013) становится
     * «оплачен»; событие {@code order.paid} уходит в Outbox той же транзакцией. Статус перепроверяется под блокировкой строки: между решением
     * оркестратора и этой транзакцией мог сработать сторож или прийти другое событие заказа.
     *
     * @param paidAt момент, когда заказ стал оплаченным: от него отсчитывается контроль выдачи 30 минут (для поздней оплаты он начинается заново)
     * @return заказ после оплаты или пусто, если статус уже другой (повтор, оплата уже учтена, возврат)
     */
    public Optional<StoredOrder> pay(UUID orderId, Instant paidAt) {
        return transaction.execute(() -> {
            StoredOrder order = store.lock(orderId).orElse(null);
            if (order == null) {
                return Optional.<StoredOrder>empty();
            }
            Optional<Transition> transition = OrderStateMachine.find(order.status(), OrderStatus.PAID, null);
            if (transition.isEmpty() || !store.pay(orderId, order.status(), paidAt)) {
                ignored("T4", order.status());
                return Optional.<StoredOrder>empty();
            }
            boolean late = OrderStatus.CANCELLED.equals(order.status());
            StoredOrder updated = store.find(orderId).orElseThrow(() -> new IllegalStateException("Заказ пропал после оплаты"));
            transaction.publish(OrderEvents.TOPIC, OrderEvents.AGGREGATE, orderId.toString(), OrderEvents.PAID, OrderEvents.SCHEMA_VERSION,
                    OrderEvents.paid(updated, late));
            meters.counter("orders_paid_total", "transition", transition.get().id()).increment();
            if (late) {
                meters.counter("late_payments_total", "result", "delivered").increment();
            }
            return Optional.of(updated);
        });
    }

    /**
     * Поздняя оплата без ключей (ADR-013, E7): деньги получены, выдать нечего. Заказ «ожидает оплаты» сначала отменяется (T6, резерв уже
     * снят), заказ «отменён» остаётся как есть; затем в Outbox уходит команда {@code order.refund-requested} на всю сумму заказа. Возврат
     * подтвердит {@code payment.refunded}, и заказ станет «возвращён» (T8).
     *
     * @return заказ после отмены или пусто, если заказ уже оплачен, возвращён или не найден (команда не издаётся)
     */
    public Optional<StoredOrder> requestRefund(UUID orderId, String refundReason) {
        return transaction.execute(() -> {
            StoredOrder order = store.lock(orderId).orElse(null);
            if (order == null) {
                return Optional.<StoredOrder>empty();
            }
            StoredOrder current = order;
            if (OrderStatus.AWAITING_PAYMENT.equals(order.status())) {
                current = cancelLocked(order, CancelReason.RESERVATION_EXPIRED).orElse(null);
            }
            if (current == null || !OrderStatus.CANCELLED.equals(current.status())) {
                ignored("refund", order.status());
                return Optional.<StoredOrder>empty();
            }
            transaction.publish(OrderEvents.TOPIC, OrderEvents.AGGREGATE, orderId.toString(), OrderEvents.REFUND_REQUESTED, OrderEvents.SCHEMA_VERSION,
                    OrderEvents.refundRequested(current, refundReason));
            meters.counter("late_payments_total", "result", "refunded").increment();
            return Optional.of(current);
        });
    }

    /**
     * T8: деньги вернулись покупателю. Заказ «отменён» становится «возвращён», событие {@code order.refunded} уходит в Outbox той же транзакцией.
     *
     * @return заказ после возврата или пусто, если заказ не «отменён» (возврат уже учтён или заказ не подлежит возврату в R1)
     */
    public Optional<StoredOrder> refund(UUID orderId, String refundReason) {
        return transaction.execute(() -> {
            StoredOrder order = store.lock(orderId).orElse(null);
            if (order == null) {
                return Optional.<StoredOrder>empty();
            }
            Optional<Transition> transition = OrderStateMachine.find(order.status(), OrderStatus.REFUNDED, null);
            if (transition.isEmpty() || !store.refund(orderId)) {
                ignored("T8", order.status());
                return Optional.<StoredOrder>empty();
            }
            StoredOrder updated = store.find(orderId).orElseThrow(() -> new IllegalStateException("Заказ пропал после возврата"));
            transaction.publish(OrderEvents.TOPIC, OrderEvents.AGGREGATE, orderId.toString(), OrderEvents.REFUNDED, OrderEvents.SCHEMA_VERSION,
                    OrderEvents.refunded(updated, refundReason));
            meters.counter("orders_refunded_total", "reason", refundReason).increment();
            return Optional.of(updated);
        });
    }

    /**
     * T9: провайдер принял письмо с ключом. Заказ «оплачен» становится «выдан», момент первой выдачи фиксируется один раз (INV-17), событие
     * {@code order.issued} уходит в Outbox. Повторная выдача заказа «выдан» (повторная отправка по обращению) ничего не меняет.
     *
     * @return заказ после выдачи или пусто, если заказ не «оплачен»
     */
    public Optional<StoredOrder> issue(UUID orderId, UUID deliveryId, Instant issuedAt) {
        return transaction.execute(() -> {
            StoredOrder order = store.lock(orderId).orElse(null);
            if (order == null) {
                return Optional.<StoredOrder>empty();
            }
            Optional<Transition> transition = OrderStateMachine.find(order.status(), OrderStatus.ISSUED, null);
            if (transition.isEmpty() || !store.issue(orderId, issuedAt)) {
                ignored("T9", order.status());
                return Optional.<StoredOrder>empty();
            }
            StoredOrder updated = store.find(orderId).orElseThrow(() -> new IllegalStateException("Заказ пропал после выдачи"));
            transaction.publish(OrderEvents.TOPIC, OrderEvents.AGGREGATE, orderId.toString(), OrderEvents.ISSUED, OrderEvents.SCHEMA_VERSION,
                    OrderEvents.issued(updated, deliveryId));
            meters.counter("orders_issued_total").increment();
            return Optional.of(updated);
        });
    }

    /**
     * INV-44: пользователь обезличен, снимок его адреса во всех его заказах заменяется. Статус заказа не меняется, события нет (аудит ведёт
     * владелец данных, «Платформа»). Повтор безвреден: заказы с уже заменённым адресом не затрагиваются.
     *
     * @return число изменённых заказов
     */
    public int anonymize(UUID buyerId) {
        return transaction.execute(() -> {
            int changed = store.anonymizeAddress(buyerId, ANONYMIZED_ADDRESS);
            meters.counter("orders_anonymized_total").increment(changed);
            return changed;
        });
    }

    /** Повторный или запоздавший вызов: переход не выполнен, заказ не тронут. */
    private void ignored(String transition, String status) {
        LOG.info("Переход {} не выполнен: статус заказа {}", transition, status);
        meters.counter("orders_transitions_ignored_total", "transition", transition, "status", status).increment();
    }

    private void count(String name, String tag, String value) {
        meters.counter(name, tag, value).increment();
    }
}
