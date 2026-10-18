package dgm.payment.payments.session;

import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.gateway.GatewayPayment;
import dgm.payment.payments.gateway.GatewayRejectedException;
import dgm.payment.payments.gateway.GatewayUnavailableException;
import dgm.payment.payments.gateway.PaymentGateway;
import dgm.payment.payments.repository.PaymentRepository;
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
 * идентификатор записывается один раз и только платежу «создан». События и отложенные действия появляются только после успешной
 * транзакции (при исключении всё отбрасывается, как откат). Вызов шлюза внутри транзакции проваливает тест.
 */
final class FakePayments {

    final Payments payments = new Payments();
    final Tx transaction = new Tx();
    final Gateway gateway = new Gateway();

    /** Платежи в памяти. */
    final class Payments implements PaymentRepository {
        final Map<UUID, Payment> byOrder = new LinkedHashMap<>();

        @Override
        public Optional<Payment> findByOrder(UUID orderId) {
            return Optional.ofNullable(byOrder.get(orderId));
        }

        @Override
        public boolean insertIfAbsent(Payment payment, Instant at) {
            return byOrder.putIfAbsent(payment.orderId(), payment) == null;
        }

        @Override
        public Optional<Payment> attachSession(UUID id, String gatewayPaymentId, String sessionUrl, Instant sessionExpiresAt) {
            for (Map.Entry<UUID, Payment> entry : byOrder.entrySet()) {
                Payment p = entry.getValue();
                if (p.id().equals(id) && p.isCreated() && p.gatewayPaymentId() == null) {
                    Payment updated = new Payment(p.id(), p.orderId(), gatewayPaymentId, p.amount(), p.currency(), sessionUrl, sessionExpiresAt, p.status());
                    entry.setValue(updated);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        }

        /** Подмена состояния платежа из теста: вебхук или сверка уже сменили статус. */
        void put(Payment payment) {
            byOrder.put(payment.orderId(), payment);
        }
    }

    /** Транзакция без базы: признак «внутри транзакции» и список записанных событий. */
    final class Tx implements PaymentTransaction {
        boolean active;
        int committed;
        int rolledBack;
        final List<Event> events = new ArrayList<>();
        private final List<Event> pending = new ArrayList<>();
        private final List<Runnable> after = new ArrayList<>();

        record Event(String topic, String aggregateType, String aggregateId, String type, int schemaVersion, Map<String, Object> data) {
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
    final class Gateway implements PaymentGateway {
        record Call(UUID paymentId, UUID orderId, long amount, String currency, String returnUrl, Instant expiresAt) {
        }

        final List<Call> calls = new ArrayList<>();
        final Map<UUID, GatewayPayment> created = new LinkedHashMap<>();
        int failNext;
        int rejectNext;
        /** Платёж у шлюза создаётся, а ответ теряется (результат неизвестен). */
        boolean loseResponse;

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
            if (loseResponse) {
                loseResponse = false;
                throw new GatewayUnavailableException("Платёжный шлюз не ответил: io_error", null);
            }
            return payment;
        }
    }
}
