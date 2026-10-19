package dgm.order.orders.service;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
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
            if (order == null) {
                return Optional.<StoredOrder>empty();
            }
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
        });
    }

    private void count(String name, String tag, String value) {
        meters.counter(name, tag, value).increment();
    }
}
