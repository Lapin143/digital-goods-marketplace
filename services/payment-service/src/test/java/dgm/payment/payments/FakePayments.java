package dgm.payment.payments;

import dgm.payment.payments.domain.ManualRefund;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.domain.RefundAttempt;
import dgm.payment.payments.domain.RefundPayment;
import dgm.payment.payments.gateway.GatewayPayment;
import dgm.payment.payments.gateway.GatewayPaymentState;
import dgm.payment.payments.gateway.GatewayRefund;
import dgm.payment.payments.gateway.GatewayRejectedException;
import dgm.payment.payments.gateway.GatewayStatus;
import dgm.payment.payments.gateway.GatewayUnavailableException;
import dgm.payment.payments.gateway.PaymentGateway;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.repository.PaymentRepository;
import dgm.payment.payments.repository.RefundRepository;
import dgm.payment.payments.repository.UnmatchedNotificationRepository;
import dgm.payment.payments.service.PaymentTransaction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Память вместо базы, Outbox и шлюза для проверок сервиса платежей. Правила те же, что у SQL: один платёж на заказ, внешний
 * идентификатор записывается один раз, пара «платёж, тип события» уникальна. События и отложенные действия появляются только после
 * успешной транзакции (при исключении всё отбрасывается, как откат). Вызов шлюза внутри транзакции проваливает тест.
 */
public final class FakePayments {

    public final Payments payments = new Payments();
    public final Events events = new Events();
    public final Unmatched unmatched = new Unmatched();
    public final Refunds refunds = new Refunds();
    public final Tx transaction = new Tx();
    public final Gateway gateway = new Gateway();

    /** Подтверждённый платёж виден и вебхуку (по внешнему идентификатору), и возвратам. */
    public RefundPayment confirmedPayment(UUID paymentId, UUID orderId, String gatewayPaymentId, long amount) {
        payments.putCreated(new Payment(paymentId, orderId, gatewayPaymentId, amount, "RUB", "https://x/pay", Instant.EPOCH, Payment.CONFIRMED), Instant.EPOCH);
        return refunds.putConfirmed(paymentId, orderId, gatewayPaymentId, amount);
    }

    /** Платежи в памяти. */
    public final class Payments implements PaymentRepository {
        public final Map<UUID, Payment> byOrder = new LinkedHashMap<>();
        public final Map<UUID, Instant> createdAt = new LinkedHashMap<>();
        public final Map<UUID, Instant> decidedAt = new LinkedHashMap<>();

        @Override
        public Optional<Payment> findByOrder(UUID orderId) {
            return Optional.ofNullable(byOrder.get(orderId));
        }

        @Override
        public boolean insertIfAbsent(Payment payment, Instant at) {
            boolean inserted = byOrder.putIfAbsent(payment.orderId(), payment) == null;
            if (inserted) {
                createdAt.put(payment.orderId(), at);
            }
            return inserted;
        }

        @Override
        public Optional<Payment> attachSession(UUID id, String gatewayPaymentId, String sessionUrl, Instant sessionExpiresAt) {
            for (Map.Entry<UUID, Payment> entry : byOrder.entrySet()) {
                Payment p = entry.getValue();
                if (p.id().equals(id) && p.sessionUrl() == null && (p.gatewayPaymentId() == null || p.gatewayPaymentId().equals(gatewayPaymentId))) {
                    Payment updated = new Payment(p.id(), p.orderId(), gatewayPaymentId, p.amount(), p.currency(), sessionUrl, sessionExpiresAt, p.status());
                    entry.setValue(updated);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId) {
            return byOrder.values().stream().filter(p -> gatewayPaymentId.equals(p.gatewayPaymentId())).findFirst();
        }

        @Override
        public Optional<Payment> lockByOrder(UUID orderId) {
            return findByOrder(orderId);
        }

        @Override
        public Optional<Payment> adoptGatewayPaymentId(UUID id, String gatewayPaymentId) {
            for (Map.Entry<UUID, Payment> entry : byOrder.entrySet()) {
                Payment p = entry.getValue();
                if (p.id().equals(id) && p.gatewayPaymentId() == null) {
                    Payment updated = new Payment(p.id(), p.orderId(), gatewayPaymentId, p.amount(), p.currency(), p.sessionUrl(), p.sessionExpiresAt(), p.status());
                    entry.setValue(updated);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<Payment> confirm(UUID id, Instant at) {
            return move(id, Payment.CONFIRMED, at);
        }

        @Override
        public Optional<Payment> reject(UUID id, Instant at) {
            return move(id, Payment.REJECTED, at);
        }

        private Optional<Payment> move(UUID id, String status, Instant at) {
            for (Map.Entry<UUID, Payment> entry : byOrder.entrySet()) {
                Payment p = entry.getValue();
                if (p.id().equals(id) && p.isCreated()) {
                    Payment updated = new Payment(p.id(), p.orderId(), p.gatewayPaymentId(), p.amount(), p.currency(), p.sessionUrl(), p.sessionExpiresAt(), status);
                    entry.setValue(updated);
                    decidedAt.put(p.orderId(), at);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        }

        @Override
        public List<Payment> openBetween(Instant createdAfter, Instant createdBefore, int limit) {
            return byOrder.values().stream()
                    .filter(p -> p.isCreated() && p.gatewayPaymentId() != null)
                    .filter(p -> {
                        Instant at = createdAt.get(p.orderId());
                        return at != null && !at.isBefore(createdAfter) && at.isBefore(createdBefore);
                    })
                    .limit(limit)
                    .toList();
        }

        /** Подмена состояния платежа из теста: вебхук или сверка уже сменили статус. */
        public void put(Payment payment) {
            byOrder.put(payment.orderId(), payment);
        }

        /** Платёж с внешним идентификатором, созданный в заданный момент. */
        public Payment putCreated(Payment payment, Instant at) {
            byOrder.put(payment.orderId(), payment);
            createdAt.put(payment.orderId(), at);
            return payment;
        }
    }

    /** Обработанные уведомления в памяти. */
    public final class Events implements PaymentEventRepository {
        public record Row(String gatewayPaymentId, String eventType, String gatewayEventId, UUID paymentId, String outcome, Instant occurredAt) {
        }

        public final Map<String, Row> rows = new LinkedHashMap<>();

        @Override
        public boolean exists(String gatewayPaymentId, String eventType) {
            return rows.containsKey(gatewayPaymentId + "|" + eventType);
        }

        @Override
        public boolean insertIfAbsent(String gatewayPaymentId, String eventType, String gatewayEventId, UUID paymentId, String outcome, Instant occurredAt,
                                      Instant receivedAt) {
            return rows.putIfAbsent(gatewayPaymentId + "|" + eventType, new Row(gatewayPaymentId, eventType, gatewayEventId, paymentId, outcome, occurredAt)) == null;
        }

        public Row row(String gatewayPaymentId, String eventType) {
            return rows.get(gatewayPaymentId + "|" + eventType);
        }
    }

    /** Несопоставленные уведомления в памяти. */
    public final class Unmatched implements UnmatchedNotificationRepository {
        public final Map<UUID, Item> items = new LinkedHashMap<>();

        public static final class Item {
            public final UnmatchedNotificationRepository.Unmatched notification;
            public Instant matchedAt;
            public Instant alertedAt;

            Item(UnmatchedNotificationRepository.Unmatched notification) {
                this.notification = notification;
            }
        }

        @Override
        public boolean insertIfAbsent(UnmatchedNotificationRepository.Unmatched n) {
            boolean exists = items.values().stream().anyMatch(i -> i.notification.gatewayPaymentId().equals(n.gatewayPaymentId())
                    && i.notification.eventType().equals(n.eventType()));
            if (exists) {
                return false;
            }
            items.put(n.id(), new Item(n));
            return true;
        }

        @Override
        public List<UnmatchedNotificationRepository.Unmatched> pending(int limit) {
            return items.values().stream().filter(i -> i.matchedAt == null).map(i -> i.notification).limit(limit).toList();
        }

        @Override
        public void markMatched(UUID id, Instant at) {
            // как в базе (ck_unmatched_notification_alert): сопоставленное уведомление оповещения не несёт
            items.get(id).matchedAt = at;
            items.get(id).alertedAt = null;
        }

        @Override
        public List<UnmatchedNotificationRepository.Unmatched> overdueWithoutAlert(Instant receivedBefore, int limit) {
            return items.values().stream().filter(i -> i.matchedAt == null && i.alertedAt == null && i.notification.receivedAt().isBefore(receivedBefore))
                    .map(i -> i.notification).limit(limit).toList();
        }

        @Override
        public void markAlerted(UUID id, Instant at) {
            items.get(id).alertedAt = at;
        }
    }

    /**
     * Возвраты в памяти. Правила те же, что у SQL: открытая попытка по платежу одна, номер попытки уникален, завершённая попытка не меняется,
     * возвращённый платёж не остаётся в очереди администратора, внешний идентификатор возврата записывается один раз.
     */
    public final class Refunds implements RefundRepository {
        public final Map<UUID, RefundPayment> byId = new LinkedHashMap<>();
        public final Map<UUID, RefundAttempt> attempts = new LinkedHashMap<>();
        public final Map<UUID, Instant> finishedAt = new LinkedHashMap<>();
        /** Исключение, которое база бросает при смене статуса (например, нарушение уникальности внешнего идентификатора возврата). */
        public RuntimeException failOnMark;

        /** Подтверждённый платёж, по которому можно запросить возврат. */
        public RefundPayment putConfirmed(UUID paymentId, UUID orderId, String gatewayPaymentId, long amount) {
            RefundPayment payment = new RefundPayment(paymentId, orderId, gatewayPaymentId, amount, "RUB", Payment.CONFIRMED, null, null, false, null, null);
            byId.put(paymentId, payment);
            return payment;
        }

        public void put(RefundPayment payment) {
            byId.put(payment.id(), payment);
        }

        public RefundPayment payment(UUID paymentId) {
            return byId.get(paymentId);
        }

        /** Попытки платежа по номеру. */
        public List<RefundAttempt> attemptsOf(UUID paymentId) {
            return attempts.values().stream().filter(a -> a.paymentId().equals(paymentId)).sorted((a, b) -> Integer.compare(a.attemptNo(), b.attemptNo())).toList();
        }

        @Override
        public Optional<RefundPayment> findById(UUID paymentId) {
            return Optional.ofNullable(byId.get(paymentId));
        }

        @Override
        public Optional<RefundPayment> lockByOrder(UUID orderId) {
            return byId.values().stream().filter(p -> p.orderId().equals(orderId)).findFirst();
        }

        @Override
        public Optional<RefundPayment> lockById(UUID paymentId) {
            return findById(paymentId);
        }

        @Override
        public boolean recordReason(UUID paymentId, String reason) {
            RefundPayment p = byId.get(paymentId);
            if (p == null || !p.refundable() || p.refundReason() != null) {
                return false;
            }
            byId.put(paymentId, new RefundPayment(p.id(), p.orderId(), p.gatewayPaymentId(), p.amount(), p.currency(), p.status(), reason, p.gatewayRefundId(),
                    p.inAdminQueue(), p.escalatedAt(), p.refundedAt()));
            return true;
        }

        @Override
        public boolean insertAttempt(RefundAttempt attempt, Instant at) {
            boolean numberTaken = attempts.values().stream().anyMatch(a -> a.paymentId().equals(attempt.paymentId()) && a.attemptNo() == attempt.attemptNo());
            boolean openTaken = attempt.open() && attempts.values().stream().anyMatch(a -> a.paymentId().equals(attempt.paymentId()) && a.open());
            if (numberTaken || openTaken) {
                return false;
            }
            attempts.put(attempt.id(), attempt);
            return true;
        }

        @Override
        public List<RefundAttempt> claimDue(Instant now, Duration lease, int limit) {
            List<RefundAttempt> due = attempts.values().stream().filter(a -> a.open() && !a.nextAttemptAt().isAfter(now))
                    .sorted((a, b) -> a.nextAttemptAt().compareTo(b.nextAttemptAt())).limit(limit).toList();
            List<RefundAttempt> claimed = new ArrayList<>();
            for (RefundAttempt a : due) {
                RefundAttempt taken = new RefundAttempt(a.id(), a.paymentId(), a.attemptNo(), RefundAttempt.IN_PROGRESS, now.plus(lease), a.errorCode());
                attempts.put(a.id(), taken);
                claimed.add(taken);
            }
            return claimed;
        }

        @Override
        public boolean finishAttempt(UUID attemptId, String status, String errorCode, Instant at) {
            RefundAttempt a = attempts.get(attemptId);
            if (a == null || !(a.open() || RefundAttempt.PROCESSING.equals(a.status()))) {
                return false;
            }
            attempts.put(attemptId, new RefundAttempt(a.id(), a.paymentId(), a.attemptNo(), status, null, errorCode));
            finishedAt.put(attemptId, at);
            return true;
        }

        @Override
        public int closeOpenAttempts(UUID paymentId, Instant at) {
            int closed = 0;
            for (RefundAttempt a : List.copyOf(attempts.values())) {
                if (a.paymentId().equals(paymentId) && (a.open() || RefundAttempt.PROCESSING.equals(a.status()))) {
                    finishAttempt(a.id(), RefundAttempt.SUCCEEDED, null, at);
                    closed++;
                }
            }
            return closed;
        }

        @Override
        public boolean attachRefundId(UUID paymentId, String gatewayRefundId) {
            RefundPayment p = byId.get(paymentId);
            if (p == null || !p.refundable() || p.gatewayRefundId() != null) {
                return false;
            }
            byId.put(paymentId, new RefundPayment(p.id(), p.orderId(), p.gatewayPaymentId(), p.amount(), p.currency(), p.status(), p.refundReason(), gatewayRefundId,
                    p.inAdminQueue(), p.escalatedAt(), p.refundedAt()));
            return true;
        }

        @Override
        public Optional<RefundPayment> markRefunded(UUID paymentId, String gatewayRefundId, Instant at) {
            if (failOnMark != null) {
                throw failOnMark;
            }
            RefundPayment p = byId.get(paymentId);
            if (p == null || !p.refundable()) {
                return Optional.empty();
            }
            if (p.refundReason() == null) {
                throw new IllegalStateException("ck_payment_refunded: у возвращённого платежа должна быть причина возврата");
            }
            RefundPayment changed = new RefundPayment(p.id(), p.orderId(), p.gatewayPaymentId(), p.amount(), p.currency(), Payment.REFUNDED, p.refundReason(),
                    p.gatewayRefundId() != null ? p.gatewayRefundId() : gatewayRefundId, false, p.escalatedAt(), at);
            byId.put(paymentId, changed);
            return Optional.of(changed);
        }

        @Override
        public boolean escalate(UUID paymentId, Instant at) {
            RefundPayment p = byId.get(paymentId);
            if (p == null || !p.refundable() || p.inAdminQueue()) {
                return false;
            }
            byId.put(paymentId, new RefundPayment(p.id(), p.orderId(), p.gatewayPaymentId(), p.amount(), p.currency(), p.status(), p.refundReason(),
                    p.gatewayRefundId(), true, at, p.refundedAt()));
            return true;
        }

        @Override
        public int attemptCount(UUID paymentId) {
            return attemptsOf(paymentId).size();
        }

        @Override
        public List<RefundPayment> awaitingConfirmation(Instant processingBefore, int limit) {
            return byId.values().stream().filter(RefundPayment::refundable).filter(p -> attemptsOf(p.id()).stream()
                    .anyMatch(a -> RefundAttempt.PROCESSING.equals(a.status()) && !finishedAt.get(a.id()).isAfter(processingBefore))).limit(limit).toList();
        }

        @Override
        public List<ManualRefund> queue(Optional<QueueCursor> after, int limit) {
            return byId.values().stream().filter(RefundPayment::inAdminQueue)
                    .sorted((a, b) -> {
                        int byTime = a.escalatedAt().compareTo(b.escalatedAt());
                        return byTime != 0 ? byTime : a.id().compareTo(b.id());
                    })
                    .filter(p -> after.isEmpty() || p.escalatedAt().isAfter(after.get().escalatedAt())
                            || (p.escalatedAt().equals(after.get().escalatedAt()) && p.id().compareTo(after.get().paymentId()) > 0))
                    .limit(limit)
                    .map(p -> new ManualRefund(p.id(), p.orderId(), p.amount(), p.currency(), p.refundReason(), attemptCount(p.id()), p.escalatedAt()))
                    .toList();
        }
    }

    /** Транзакция без базы: признак «внутри транзакции» и список записанных событий. */
    public final class Tx implements PaymentTransaction {
        public boolean active;
        public int committed;
        public int rolledBack;
        public final List<Event> events = new ArrayList<>();
        private final List<Event> pending = new ArrayList<>();
        private final List<Runnable> after = new ArrayList<>();

        public record Event(String topic, String aggregateType, String aggregateId, String type, int schemaVersion, Map<String, Object> data) {
        }

        @Override
        public <T> T execute(Supplier<T> action) {
            if (active) {
                throw new IllegalStateException("Вложенная транзакция в тесте не предусмотрена");
            }
            active = true;
            try {
                T result = action.get();
                events.addAll(pending);
                committed++;
                List<Runnable> run = new ArrayList<>(after);
                pending.clear();
                after.clear();
                active = false;
                run.forEach(Runnable::run);
                return result;
            } catch (RuntimeException e) {
                rolledBack++;
                pending.clear();
                after.clear();
                throw e;
            } finally {
                active = false;
            }
        }

        @Override
        public void afterCommit(Runnable action) {
            if (active) {
                after.add(action);
            } else {
                action.run();
            }
        }

        @Override
        public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
            if (!active) {
                throw new IllegalStateException("Событие Outbox пишется только внутри транзакции");
            }
            pending.add(new Event(topic, aggregateType, aggregateId, eventType, schemaVersion, data));
        }
    }

    /** Шлюз в памяти: платёж у шлюза создаётся один на ключ идемпотентности, сбои включает тест. */
    public final class Gateway implements PaymentGateway {
        public record Call(UUID paymentId, UUID orderId, long amount, String currency, String returnUrl, Instant expiresAt) {
        }

        public final List<Call> calls = new ArrayList<>();
        public final Map<UUID, GatewayPayment> created = new LinkedHashMap<>();
        public int failNext;
        public int rejectNext;
        /** Платёж у шлюза создаётся, а ответ теряется (результат неизвестен). */
        public boolean loseResponse;
        /** Выполняется внутри вызова создания до ответа: тест подставляет то, что произошло параллельно. */
        public Runnable duringCreate = () -> { };

        /** Состояния платежей у шлюза по внешнему идентификатору; нет записи: шлюз такого платежа не знает. */
        public final Map<String, GatewayPaymentState> states = new LinkedHashMap<>();
        /** Сколько запросов статуса подряд остаются без ответа. */
        public int statusFailures;
        public final List<String> statusCalls = new ArrayList<>();
        public int singleAttemptCalls;
        public int retryingCalls;

        @Override
        public GatewayPayment create(UUID paymentId, UUID orderId, long amount, String currency, String returnUrl, Instant expiresAt) {
            if (transaction.active) {
                throw new AssertionError("Вызов шлюза внутри транзакции базы (правило 9)");
            }
            calls.add(new Call(paymentId, orderId, amount, currency, returnUrl, expiresAt));
            if (rejectNext > 0) {
                rejectNext--;
                throw new GatewayRejectedException(400, "Платёжный шлюз отклонил запрос, код 400");
            }
            if (failNext > 0) {
                failNext--;
                throw new GatewayUnavailableException("Платёжный шлюз не ответил: timeout", null);
            }
            GatewayPayment payment = created.computeIfAbsent(paymentId,
                    id -> new GatewayPayment("pay_" + (created.size() + 1), "https://localhost:8443/payment/pay/pay_" + (created.size() + 1), expiresAt));
            duringCreate.run();
            if (loseResponse) {
                loseResponse = false;
                throw new GatewayUnavailableException("Платёжный шлюз не ответил: io_error", null);
            }
            return payment;
        }

        /** Шлюз знает платёж в заданном статусе. */
        public void know(String gatewayPaymentId, GatewayStatus status, long amount, String currency) {
            states.put(gatewayPaymentId, new GatewayPaymentState(gatewayPaymentId, status, amount, currency));
        }

        @Override
        public GatewayPaymentState checkStatus(String gatewayPaymentId) {
            singleAttemptCalls++;
            return status(gatewayPaymentId);
        }

        @Override
        public GatewayPaymentState lookupStatus(String gatewayPaymentId) {
            retryingCalls++;
            return status(gatewayPaymentId);
        }

        /** Возвраты у шлюза: один на платёж (ключ идемпотентности), сбои включает тест. */
        public final Map<UUID, GatewayRefund> refundsCreated = new LinkedHashMap<>();
        public final List<UUID> refundCalls = new ArrayList<>();
        /** Сколько следующих запросов возврата шлюз отклоняет (4xx). */
        public int refundRejects;
        /** Сколько следующих запросов возврата остаются без ответа. */
        public int refundFailures;
        /** Шлюз возвращает деньги сразу ({@code true}) или принимает возврат в обработку ({@code false}). */
        public boolean refundInstant = true;

        @Override
        public GatewayRefund createRefund(UUID paymentId, String gatewayPaymentId, long amount, String currency) {
            if (transaction.active) {
                throw new AssertionError("Создание возврата у шлюза внутри транзакции базы (правило 9)");
            }
            refundCalls.add(paymentId);
            if (refundRejects > 0) {
                refundRejects--;
                throw new GatewayRejectedException(422, "Платёжный шлюз отклонил запрос, код 422");
            }
            if (refundFailures > 0) {
                refundFailures--;
                throw new GatewayUnavailableException("Платёжный шлюз не ответил: timeout", null);
            }
            return refundsCreated.computeIfAbsent(paymentId, id -> new GatewayRefund("ref_" + (refundsCreated.size() + 1), refundInstant));
        }

        private GatewayPaymentState status(String gatewayPaymentId) {
            if (transaction.active) {
                throw new AssertionError("Запрос статуса у шлюза внутри транзакции базы (правило 9)");
            }
            statusCalls.add(gatewayPaymentId);
            if (statusFailures > 0) {
                statusFailures--;
                throw new GatewayUnavailableException("Платёжный шлюз не ответил: timeout", null);
            }
            GatewayPaymentState state = states.get(gatewayPaymentId);
            if (state == null) {
                throw new GatewayRejectedException(404, "Платёжный шлюз отклонил запрос, код 404");
            }
            return state;
        }
    }
}
