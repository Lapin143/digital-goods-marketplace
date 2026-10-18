package dgm.payment.payments;

import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.gateway.GatewayPayment;
import dgm.payment.payments.gateway.GatewayPaymentState;
import dgm.payment.payments.gateway.GatewayRejectedException;
import dgm.payment.payments.gateway.GatewayStatus;
import dgm.payment.payments.gateway.GatewayUnavailableException;
import dgm.payment.payments.gateway.PaymentGateway;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.repository.PaymentRepository;
import dgm.payment.payments.repository.UnmatchedNotificationRepository;
import dgm.payment.payments.service.PaymentTransaction;
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
    public final Tx transaction = new Tx();
    public final Gateway gateway = new Gateway();

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
