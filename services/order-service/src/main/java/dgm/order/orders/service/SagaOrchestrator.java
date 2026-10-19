package dgm.order.orders.service;

import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.order.orders.client.CatalogClient;
import dgm.order.orders.client.CatalogClient.ProductCard;
import dgm.order.orders.client.InventoryClient;
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
 * Оркестратор саги покупки, шаги 1 – 3 ({@code createOrder}, SEQ-01, ADR-004): знает порядок шагов и вызывает соседей, а статус заказа сам не
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
