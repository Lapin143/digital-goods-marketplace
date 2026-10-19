package dgm.order.orders.service;

import dgm.kit.events.ProcessedEvents;
import dgm.order.orders.domain.NewOrder;
import dgm.order.orders.domain.OrderStatus;
import dgm.order.orders.domain.StoredOrder;
import dgm.order.orders.repository.OrderStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Заказы и Outbox в памяти для модульных тестов домена и саги. Хранилище и транзакция в одном классе: вызов хранилища вне транзакции это
 * ошибка (как в базе, где консультативная блокировка и блокировка строки живут до конца транзакции), исключение в транзакции откатывает и
 * заказы, и события. Вложенная транзакция присоединяется к внешней, как {@code TransactionTemplate} в боевом коде: так обработчик события,
 * которого каркас обернул в свою транзакцию с отметкой {@code processed_event}, вызывает переходы домена, и всё откатывается вместе.
 */
public final class FakeOrderStore implements OrderStore, OrderTransaction {

    /** Событие, записанное в Outbox. */
    public record Published(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
    }

    private Map<UUID, StoredOrder> orders = new LinkedHashMap<>();
    private Set<String> processed = new TreeSet<>();
    private final List<Published> events = new ArrayList<>();
    private final List<UUID> lockedBuyers = new ArrayList<>();
    private long nextNumber = 1001;
    private boolean inTransaction;
    private boolean failOnPublish;
    private final Set<String> failingAggregates = new TreeSet<>();
    private int transactions;

    /** Заказ, которого сага не создавала (например, оплаченный или отменённый заказ покупателя). */
    public FakeOrderStore seed(StoredOrder order) {
        orders.put(order.id(), order);
        return this;
    }

    /** Следующая запись в Outbox падает: проверка атомарности перехода и события. */
    public FakeOrderStore failOnPublish() {
        failOnPublish = true;
        return this;
    }

    /** Запись в Outbox падает только для событий этого заказа: сбой на одном заказе при исправных остальных (сторож). */
    public FakeOrderStore failOnPublishOf(UUID orderId) {
        failingAggregates.add(orderId.toString());
        return this;
    }

    /** Outbox снова исправен: прежние сбои сняты. */
    public FakeOrderStore publishWorks() {
        failOnPublish = false;
        failingAggregates.clear();
        return this;
    }

    public List<Published> events() {
        return List.copyOf(events);
    }

    public List<Published> events(String eventType) {
        return events.stream().filter(e -> e.eventType().equals(eventType)).toList();
    }

    public List<UUID> lockedBuyers() {
        return List.copyOf(lockedBuyers);
    }

    public int transactions() {
        return transactions;
    }

    /** Идёт ли сейчас транзакция: клиенты соседей в тестах саги обязаны быть вызваны вне неё (правило 9). */
    public boolean inTransaction() {
        return inTransaction;
    }

    public List<StoredOrder> all() {
        return List.copyOf(orders.values());
    }

    public StoredOrder get(UUID id) {
        return orders.get(id);
    }

    // ----------------------------------------------------------------------------------------------------- OrderTransaction

    @Override
    public <T> T execute(Supplier<T> action) {
        if (inTransaction) {
            return action.get();
        }
        Map<UUID, StoredOrder> ordersBefore = new LinkedHashMap<>(orders);
        Set<String> processedBefore = new TreeSet<>(processed);
        int eventsBefore = events.size();
        int locksBefore = lockedBuyers.size();
        inTransaction = true;
        transactions++;
        try {
            return action.get();
        } catch (RuntimeException e) {
            orders = ordersBefore;
            processed = processedBefore;
            while (events.size() > eventsBefore) {
                events.remove(events.size() - 1);
            }
            while (lockedBuyers.size() > locksBefore) {
                lockedBuyers.remove(lockedBuyers.size() - 1);
            }
            throw e;
        } finally {
            inTransaction = false;
        }
    }

    /** Транзакции каркаса событий на этом же хранилище: обработчик, отметка обработанного и переходы домена откатываются вместе. */
    public TransactionOperations operations() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> callback) {
                return FakeOrderStore.this.execute(() -> callback.doInTransaction(new SimpleTransactionStatus()));
            }
        };
    }

    /** Таблица {@code processed_event} этого хранилища: отметка пишется внутри транзакции и откатывается вместе с заказами. */
    public ProcessedEvents processedEvents() {
        return new ProcessedEvents() {
            @Override
            public boolean markProcessed(String consumer, UUID eventId) {
                requireTransaction();
                return processed.add(consumer + "/" + eventId);
            }

            @Override
            public boolean isProcessed(String consumer, UUID eventId) {
                return processed.contains(consumer + "/" + eventId);
            }
        };
    }

    /** Сколько событий обработано (строк {@code processed_event}). */
    public int processedCount() {
        return processed.size();
    }

    @Override
    public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
        requireTransaction();
        if (failOnPublish || failingAggregates.contains(aggregateId)) {
            throw new IllegalStateException("Outbox недоступен");
        }
        events.add(new Published(topic, aggregateType, aggregateId, eventType, schemaVersion, Map.copyOf(data)));
    }

    // ----------------------------------------------------------------------------------------------------- OrderStore

    @Override
    public void lockBuyer(UUID buyerId) {
        requireTransaction();
        lockedBuyers.add(buyerId);
    }

    @Override
    public int countUnpaid(UUID buyerId) {
        requireTransaction();
        return (int) orders.values().stream().filter(o -> o.buyerId().equals(buyerId) && OrderStatus.isUnpaid(o.status())).count();
    }

    @Override
    public void insert(NewOrder o) {
        requireTransaction();
        if (orders.containsKey(o.id())) {
            throw new IllegalStateException("Заказ " + o.id() + " уже есть");
        }
        orders.put(o.id(), new StoredOrder(o.id(), nextNumber++, o.buyerId(), o.sellerId(), o.productId(), o.productTitle(), o.quantity(), o.unitPrice(),
                o.amount(), o.commission(), o.currency(), OrderStatus.CREATED, null, o.deliveryAddress(), null, null, null, o.createdAt(), null, null));
    }

    @Override
    public Optional<StoredOrder> find(UUID orderId) {
        requireTransaction();
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public Optional<StoredOrder> lock(UUID orderId) {
        requireTransaction();
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public boolean awaitPayment(UUID orderId, Instant reserveUntil, Instant sessionUntil, String paymentSessionUrl) {
        requireTransaction();
        StoredOrder o = orders.get(orderId);
        if (o == null || !OrderStatus.CREATED.equals(o.status())) {
            return false;
        }
        orders.put(orderId, new StoredOrder(o.id(), o.number(), o.buyerId(), o.sellerId(), o.productId(), o.productTitle(), o.quantity(), o.unitPrice(),
                o.amount(), o.commission(), o.currency(), OrderStatus.AWAITING_PAYMENT, null, o.deliveryAddress(), reserveUntil, sessionUntil,
                paymentSessionUrl, o.createdAt(), null, null));
        return true;
    }

    @Override
    public boolean cancel(UUID orderId, String from, String reason) {
        requireTransaction();
        StoredOrder o = orders.get(orderId);
        if (o == null || !from.equals(o.status())) {
            return false;
        }
        orders.put(orderId, new StoredOrder(o.id(), o.number(), o.buyerId(), o.sellerId(), o.productId(), o.productTitle(), o.quantity(), o.unitPrice(),
                o.amount(), o.commission(), o.currency(), OrderStatus.CANCELLED, reason, o.deliveryAddress(), o.reserveUntil(), o.sessionUntil(),
                o.paymentSessionUrl(), o.createdAt(), o.paidAt(), o.issuedAt()));
        return true;
    }

    @Override
    public boolean pay(UUID orderId, String from, Instant paidAt) {
        requireTransaction();
        StoredOrder o = orders.get(orderId);
        if (o == null || !from.equals(o.status())) {
            return false;
        }
        orders.put(orderId, changed(o, OrderStatus.PAID, null, o.deliveryAddress(), paidAt, o.issuedAt()));
        return true;
    }

    @Override
    public boolean refund(UUID orderId) {
        requireTransaction();
        StoredOrder o = orders.get(orderId);
        if (o == null || !OrderStatus.CANCELLED.equals(o.status())) {
            return false;
        }
        orders.put(orderId, changed(o, OrderStatus.REFUNDED, o.cancelReason(), o.deliveryAddress(), o.paidAt(), o.issuedAt()));
        return true;
    }

    @Override
    public boolean issue(UUID orderId, Instant issuedAt) {
        requireTransaction();
        StoredOrder o = orders.get(orderId);
        if (o == null || !OrderStatus.PAID.equals(o.status())) {
            return false;
        }
        orders.put(orderId, changed(o, OrderStatus.ISSUED, o.cancelReason(), o.deliveryAddress(), o.paidAt(), issuedAt));
        return true;
    }

    @Override
    public int anonymizeAddress(UUID buyerId, String replacement) {
        requireTransaction();
        int changed = 0;
        for (StoredOrder o : List.copyOf(orders.values())) {
            if (o.buyerId().equals(buyerId) && !replacement.equals(o.deliveryAddress())) {
                orders.put(o.id(), changed(o, o.status(), o.cancelReason(), replacement, o.paidAt(), o.issuedAt()));
                changed++;
            }
        }
        return changed;
    }

    private static StoredOrder changed(StoredOrder o, String status, String cancelReason, String address, Instant paidAt, Instant issuedAt) {
        return new StoredOrder(o.id(), o.number(), o.buyerId(), o.sellerId(), o.productId(), o.productTitle(), o.quantity(), o.unitPrice(), o.amount(),
                o.commission(), o.currency(), status, cancelReason, address, o.reserveUntil(), o.sessionUntil(), o.paymentSessionUrl(), o.createdAt(), paidAt,
                issuedAt);
    }

    private void requireTransaction() {
        if (!inTransaction) {
            throw new IllegalStateException("Хранилище заказов вызвано вне транзакции");
        }
    }
}
