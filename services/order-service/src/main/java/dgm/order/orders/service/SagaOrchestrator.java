package dgm.order.orders.service;

import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.order.orders.client.CatalogClient;
import dgm.order.orders.client.CatalogClient.ProductCard;
import dgm.order.orders.client.InventoryClient;
import dgm.order.orders.client.InventoryClient.ConfirmResult;
import dgm.order.orders.client.InventoryClient.Confirmed;
import dgm.order.orders.client.InventoryClient.OutOfStock;
import dgm.order.orders.client.InventoryClient.ReserveResult;
import dgm.order.orders.client.InventoryClient.Reserved;
import dgm.order.orders.client.InventoryClient.UnknownProduct;
import dgm.order.orders.client.PaymentClient;
import dgm.order.orders.client.PaymentClient.Opened;
import dgm.order.orders.client.PaymentClient.SessionResult;
import dgm.order.orders.domain.CancelReason;
import dgm.order.orders.domain.NewOrder;
import dgm.order.orders.domain.OrderPricing;
import dgm.order.orders.domain.OrderStatus;
import dgm.order.orders.domain.RefundReason;
import dgm.order.orders.domain.StoredOrder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Оркестратор саги покупки: шаги 1 – 3 ({@code createOrder}, SEQ-01, ADR-004) и разбор событий после них (оплата, отказ, истечение резерва,
 * возврат, выдача, сторож). Шаги 1 – 3: знает порядок шагов и вызывает соседей, а статус заказа сам не
 * меняет, каждый переход просит у {@link OrderTransitions}. Соседи вызываются вне транзакций (правило 9), каждая фиксация короткая:
 * <ol>
 *   <li>карточка товара в каталоге: товар опубликован, цена из каталога, а не из запроса;</li>
 *   <li>заказ «создан» (T1, под блокировкой покупателя, лимит неоплаченных заказов), затем резерв ключей. Ключей не хватает: заказ «отменён» (T3),
 *       409 {@code insufficient-stock};</li>
 *   <li>платёжная сессия. Не открылась: заказ «отменён» (T12) и резерв снимается событием {@code order.cancelled}, 503
 *       {@code payment-session-unavailable};</li>
 *   <li>заказ «ожидает оплаты» (T2) и событие {@code order.created}.</li>
 * </ol>
 * Если сосед не ответил на резерв, заказ отменяется с причиной «системная ошибка»: резерв мог быть создан, и событие отмены его снимет. Сбой между
 * шагами, после которого отмена не прошла (падение процесса), доберёт сторож заказов (шаг 11). Шаги идемпотентны: ключи соседям выводятся из имени
 * шага и заказа, повтор вернёт прежний результат.
 *
 * <p>События разбираются по таблице «Обработка событий оркестратором» ADR-004: оркестратор смотрит на статус заказа и выбирает ветку, а
 * допустимость перехода и перепроверку статуса под блокировкой делает {@link OrderTransitions}. Повтор и перестановка событий безвредны: в
 * неподходящем статусе событие игнорируется с причиной, в метрике {@code events_ignored_total}.
 */
public final class SagaOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(SagaOrchestrator.class);

    /** Через сколько секунд вызывающему имеет смысл повторить запрос, если сосед не ответил. */
    static final int RETRY_AFTER_SECONDS = 5;

    /** Платёжная сессия кончается не позже чем за секунду до конца резерва, даже если резерв создавался долго (INV-07). */
    static final Duration SESSION_MARGIN = Duration.ofSeconds(1);

    static final String PUBLISHED = "published";
    static final String KEY_POOL = "key_pool";
    static final String RUB = "RUB";

    private final CatalogClient catalog;
    private final InventoryClient inventory;
    private final PaymentClient payments;
    private final OrderTransitions transitions;
    private final OrderSettings settings;
    private final Clock clock;
    private final MeterRegistry meters;

    public SagaOrchestrator(CatalogClient catalog, InventoryClient inventory, PaymentClient payments, OrderTransitions transitions,
                            OrderSettings settings, Clock clock, MeterRegistry meters) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.payments = payments;
        this.transitions = transitions;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * @param email снимок e-mail покупателя для доставки (персональные данные, в журнал не пишется)
     * @return заказ «ожидает оплаты» с адресом страницы оплаты и сроками
     * @throws ProblemException 409 {@code product-unavailable}, {@code insufficient-stock} (с {@code available}), {@code unpaid-orders-limit} (с
     *                          {@code limit}); 503 {@code payment-session-unavailable} и {@code dependency-unavailable} (с {@code Retry-After})
     */
    public StoredOrder create(UUID buyerId, String email, UUID productId, int quantity) {
        ProductCard card = loadCard(productId);
        long amount;
        try {
            amount = OrderPricing.total(card.price(), quantity);
        } catch (IllegalArgumentException e) {
            throw reject("price-out-of-range", productUnavailable());
        }
        long commission = OrderPricing.commission(amount, settings.commissionRateBp());
        NewOrder draft = new NewOrder(Uuids.v7(clock), buyerId, card.sellerId(), productId, card.title(), quantity, card.price(), amount,
                settings.commissionRateBp(), commission, RUB, email, clock.instant());
        StoredOrder order = transitions.place(draft);

        Reserved reserved = reserve(order);
        Opened session = openSession(order, reserved);

        Instant sessionUntil = earlier(session.expiresAt(), sessionLimit(reserved));
        Optional<StoredOrder> awaiting = transitions.awaitPayment(order.id(), reserved.expiresAt(), sessionUntil, session.url());
        if (awaiting.isEmpty()) {
            // Пока шли вызовы, заказ успели отменить (сторож): покупателю остаётся повторить запрос
            LOG.warn("Заказ {} отменён до завершения оформления", order.id());
            throw reject("cancelled-meanwhile", dependencyUnavailable());
        }
        return awaiting.get();
    }

    /** Шаг 1: карточка товара. Заказа ещё нет, компенсировать нечего. */
    private ProductCard loadCard(UUID productId) {
        Optional<ProductCard> card;
        try {
            card = catalog.card(productId);
        } catch (DependencyUnavailableException e) {
            throw reject("catalog-unavailable", dependencyUnavailable());
        }
        if (card.isEmpty() || !PUBLISHED.equals(card.get().status()) || !KEY_POOL.equals(card.get().issuanceMethod())
                || !RUB.equals(card.get().currency())) {
            throw reject("product-unavailable", productUnavailable());
        }
        return card.get();
    }

    /** Шаг 2: резерв ключей. */
    private Reserved reserve(StoredOrder order) {
        ReserveResult result;
        try {
            result = inventory.reserve(order.id(), order.productId(), order.quantity());
        } catch (DependencyUnavailableException e) {
            cancel(order, CancelReason.SYSTEM_ERROR);
            throw reject("inventory-unavailable", dependencyUnavailable());
        }
        if (result instanceof Reserved reserved) {
            return reserved;
        }
        if (result instanceof OutOfStock out) {
            cancel(order, CancelReason.OUT_OF_STOCK);
            throw reject("insufficient-stock", insufficientStock(out.available()));
        }
        if (!(result instanceof UnknownProduct)) {
            throw new IllegalStateException("Неизвестный исход резерва: " + result);
        }
        cancel(order, CancelReason.SYSTEM_ERROR);
        throw reject("product-unavailable", productUnavailable());
    }

    /** Шаг 3: платёжная сессия. Резерв уже создан, поэтому любая неудача отменяет заказ и снимает резерв (T12). */
    private Opened openSession(StoredOrder order, Reserved reserved) {
        Instant now = clock.instant();
        Instant sessionUntil = earlier(now.plus(settings.sessionTtl()), sessionLimit(reserved));
        if (!sessionUntil.isAfter(now)) {
            // Резерв почти кончился, пока он создавался: сессии негде поместиться
            cancel(order, CancelReason.SYSTEM_ERROR);
            throw reject("reservation-too-short", dependencyUnavailable());
        }
        SessionResult result;
        try {
            result = payments.open(order.id(), order.amount(), order.currency(), sessionUntil);
        } catch (DependencyUnavailableException e) {
            cancel(order, CancelReason.PAYMENT_SESSION_FAILED);
            throw reject("payment-session-unavailable", paymentSessionUnavailable());
        }
        if (result instanceof Opened opened) {
            return opened;
        }
        cancel(order, CancelReason.PAYMENT_SESSION_FAILED);
        throw reject("payment-session-refused", paymentSessionUnavailable());
    }

    // ------------------------------------------------------------------------------------------------ события и сторож (шаг 11)

    /**
     * Шаг 5, часть 1 (вне транзакции): что делать с подтверждённым платежом. Статус читается короткой транзакцией, затем для заказа
     * «ожидает оплаты» или «отменён» вызывается подтверждение резерва (ADR-013). Вызов идемпотентен, поэтому повтор события после сбоя безвреден.
     */
    public PaymentDecision decidePayment(UUID orderId) {
        Optional<StoredOrder> found = transitions.find(orderId);
        if (found.isEmpty()) {
            return anomaly("unknown-order", orderId);
        }
        StoredOrder order = found.get();
        String status = order.status();
        if (OrderStatus.CREATED.equals(status)) {
            return new PaymentDecision.Wait("order-not-awaiting-payment-yet");
        }
        if (OrderStatus.PAID.equals(status) || OrderStatus.ISSUED.equals(status)) {
            meters.counter("payment_duplicates_total").increment();
            return new PaymentDecision.Ignore("payment-already-applied");
        }
        if (OrderStatus.REFUNDED.equals(status)) {
            return anomaly("order-refunded", orderId);
        }
        if (OrderStatus.CANCELLED.equals(status) && CancelReason.PAYMENT_DECLINED.equals(order.cancelReason())) {
            return anomaly("payment-declined-order", orderId);
        }
        ConfirmResult confirm;
        try {
            confirm = inventory.confirm(order.id(), order.productId(), order.quantity());
        } catch (DependencyUnavailableException e) {
            return new PaymentDecision.Wait("inventory-unavailable");
        }
        return confirm instanceof Confirmed ? new PaymentDecision.Pay() : new PaymentDecision.Refund();
    }

    /** Шаг 5, часть 2 (внутри транзакции потребителя): T4, T7 или отмена с возвратом. Статус перепроверяется под блокировкой. */
    public SagaOutcome applyPayment(UUID orderId, PaymentDecision decision, Instant paidAt) {
        if (decision instanceof PaymentDecision.Ignore ignore) {
            return SagaOutcome.ignored(ignore.reason());
        }
        if (decision instanceof PaymentDecision.Pay) {
            return transitions.pay(orderId, paidAt).isPresent() ? SagaOutcome.applied() : SagaOutcome.ignored("order-changed");
        }
        if (decision instanceof PaymentDecision.Refund) {
            return transitions.requestRefund(orderId, RefundReason.LATE_PAYMENT).isPresent() ? SagaOutcome.applied() : SagaOutcome.ignored("order-changed");
        }
        throw new IllegalStateException("Решение " + decision + " не применяется: событие обрабатывается позже");
    }

    /** {@code payment.rejected}: шлюз отклонил платёж. «Ожидает оплаты» в «отменён» (T5); подтверждение продавцом не нужно. */
    public SagaOutcome paymentRejected(UUID orderId) {
        Optional<StoredOrder> order = transitions.find(orderId);
        if (order.isEmpty()) {
            meters.counter("payment_anomalies_total", "kind", "unknown-order").increment();
            return SagaOutcome.ignored("unknown-order");
        }
        if (OrderStatus.CREATED.equals(order.get().status())) {
            return SagaOutcome.retry("order-not-awaiting-payment-yet");
        }
        return transitions.cancel(orderId, CancelReason.PAYMENT_DECLINED).isPresent() ? SagaOutcome.applied() : SagaOutcome.ignored("order-not-awaiting-payment");
    }

    /**
     * {@code reservation.expired} и сторож: резерв кончился, а оплаты нет. «Ожидает оплаты» в «отменён» (T6). Для других статусов событие
     * относится к уже завершённой попытке и игнорируется.
     */
    public SagaOutcome reservationExpired(UUID orderId) {
        return transitions.cancel(orderId, CancelReason.RESERVATION_EXPIRED).isPresent() ? SagaOutcome.applied() : SagaOutcome.ignored("order-not-awaiting-payment");
    }

    /**
     * Сторож: заказ «создан» остался без продолжения (потерян ответ резерва или платёжной сессии, процесс упал между шагами). Отмена с причиной
     * «системная ошибка» (T12) и событие {@code order.cancelled}, по которому остатки снимут резерв, если он успел появиться.
     */
    public SagaOutcome abandonedCreated(UUID orderId) {
        return transitions.cancel(orderId, CancelReason.SYSTEM_ERROR).isPresent() ? SagaOutcome.applied() : SagaOutcome.ignored("order-not-created");
    }

    /** {@code payment.refunded}: деньги вернулись. «Отменён» в «возвращён» (T8), событие {@code order.refunded}. */
    public SagaOutcome refundConfirmed(UUID orderId, String refundReason) {
        return transitions.refund(orderId, refundReason).isPresent() ? SagaOutcome.applied() : SagaOutcome.ignored("order-not-cancelled");
    }

    /** {@code delivery.accepted}: провайдер принял письмо. «Оплачен» в «выдан» (T9), один раз за жизнь заказа; повтор для «выдан» игнорируется. */
    public SagaOutcome deliveryAccepted(UUID orderId, UUID deliveryId, Instant acceptedAt) {
        return transitions.issue(orderId, deliveryId, acceptedAt).isPresent() ? SagaOutcome.applied() : SagaOutcome.ignored("order-not-paid");
    }

    /** Подтверждение платежа, которое не должно было прийти для заказа в таком статусе: заказ не меняется, метрика и ошибка в журнале. */
    private PaymentDecision anomaly(String kind, UUID orderId) {
        meters.counter("payment_anomalies_total", "kind", kind).increment();
        LOG.error("Подтверждение платежа по заказу {} не применено: {}", orderId, kind);
        return new PaymentDecision.Ignore(kind);
    }

    private Instant sessionLimit(Reserved reserved) {
        return reserved.expiresAt().minus(SESSION_MARGIN);
    }

    private void cancel(StoredOrder order, String reason) {
        transitions.cancel(order.id(), reason);
    }

    private ProblemException reject(String reason, ProblemException problem) {
        meters.counter("orders_rejected_total", "reason", reason).increment();
        return problem;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static ProblemException productUnavailable() {
        return new ProblemException(ProblemType.PRODUCT_UNAVAILABLE, "Товар недоступен для заказа: он снят с продажи или не продаётся.");
    }

    private static ProblemException insufficientStock(int available) {
        return new ProblemException(ProblemType.INSUFFICIENT_STOCK, "Недостаточно ключей: доступно " + available + ".",
                Map.of("available", available), Map.of());
    }

    private static ProblemException dependencyUnavailable() {
        return ProblemException.dependencyUnavailable("Не удалось оформить заказ, повторите запрос позже.", RETRY_AFTER_SECONDS);
    }

    private static ProblemException paymentSessionUnavailable() {
        return new ProblemException(ProblemType.PAYMENT_SESSION_UNAVAILABLE, "Не удалось открыть оплату, заказ отменён.", Map.of(),
                Map.of("Retry-After", Integer.toString(RETRY_AFTER_SECONDS)));
    }
}
