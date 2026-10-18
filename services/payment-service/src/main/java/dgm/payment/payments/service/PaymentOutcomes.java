package dgm.payment.payments.service;

import dgm.kit.time.Timestamps;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.domain.PaymentStateMachine;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Общий код применения исхода платежа: им пользуются и вебхук шлюза, и сверка со шлюзом (ADR-015, «общий код, тот же дедуп»). Одна
 * транзакция делает три вещи вместе: запись обработанного уведомления в {@code payment_event} (дедупликация по паре «внешний
 * идентификатор платежа, тип события», INV-14), смена статуса по SM-02 и запись события {@code payment.confirmed} или
 * {@code payment.rejected} в Outbox (ADR-005). Строка платежа блокируется {@code FOR UPDATE} на время транзакции, поэтому два
 * одинаковых уведомления идут друг за другом, и второе находит запись первого.
 *
 * <p>Вызовов шлюза здесь нет (правило 9): запрос статуса делает вызывающий до транзакции.
 */
public final class PaymentOutcomes {

    /** Тема событий платежей. */
    public static final String TOPIC = "payment.events";
    static final String AGGREGATE = "Payment";
    static final int SCHEMA_VERSION = 1;

    /** Что шлюз сообщил о платеже. */
    public enum Target {
        /** Платёж оплачен: {@code created → confirmed}. */
        CONFIRM(Payment.CONFIRMED, "payment.confirmed"),
        /** Платёж отклонён: {@code created → rejected}. */
        REJECT(Payment.REJECTED, "payment.rejected");

        private final String status;
        private final String event;

        Target(String status, String event) {
            this.status = status;
            this.event = event;
        }

        String status() {
            return status;
        }

        String event() {
            return event;
        }
    }

    /** Исход применения. */
    public enum Result {
        /** Статус изменён, событие записано. */
        APPLIED,
        /** Такая пара «платёж, тип события» уже обработана: ничего не изменено. */
        DUPLICATE,
        /** Уведомление не подходит платежу (статус или внешний идентификатор): статус не изменён, разбор вручную. */
        ANOMALY
    }

    /**
     * Что применить.
     *
     * @param gatewayPaymentId внешний идентификатор платежа
     * @param eventType        тип события для дедупликации (у сверки свои типы, чтобы аномалия вебхука не закрыла сверке путь)
     * @param gatewayEventId   идентификатор уведомления у шлюза, для разбора
     * @param target           что сообщил шлюз
     * @param orderId          заказ платежа
     * @param occurredAt       время события по данным шлюза; для сверки время запроса
     * @param failureReason    причина отказа из перечисления контракта или {@code null}
     */
    public record Application(String gatewayPaymentId, String eventType, String gatewayEventId, Target target, UUID orderId, Instant occurredAt,
                              String failureReason) {
    }

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final PaymentTransaction transaction;
    private final Clock clock;
    private final MeterRegistry meters;

    public PaymentOutcomes(PaymentRepository payments, PaymentEventRepository events, PaymentTransaction transaction, Clock clock, MeterRegistry meters) {
        this.payments = payments;
        this.events = events;
        this.transaction = transaction;
        this.clock = clock;
        this.meters = meters;
    }

    public Result apply(Application application) {
        Outcome outcome = transaction.execute(() -> applyLocked(application));
        if (outcome.anomalyReason != null) {
            meters.counter("payment_anomalies_total", "reason", outcome.anomalyReason).increment();
        }
        return outcome.result;
    }

    private record Outcome(Result result, String anomalyReason) {
    }

    private Outcome applyLocked(Application a) {
        Payment payment = payments.lockByOrder(a.orderId()).orElseThrow(() -> new IllegalStateException("Платёж заказа не найден"));
        Instant now = clock.instant();
        // Тот же исход уже применён другим путём (вебхук и сверка пришли к одному платежу): это повтор, а не аномалия
        if (payment.status().equals(a.target().status()) || (a.target() == Target.CONFIRM && Payment.REFUNDED.equals(payment.status()))) {
            return new Outcome(Result.DUPLICATE, null);
        }
        String mismatch = payment.gatewayPaymentId() == null || !payment.gatewayPaymentId().equals(a.gatewayPaymentId()) ? "gateway-id-mismatch" : null;
        boolean allowed = mismatch == null && PaymentStateMachine.allowed(payment.status(), a.target().status());
        String recorded = allowed ? PaymentEventRepository.APPLIED : PaymentEventRepository.ANOMALY;
        if (!events.insertIfAbsent(a.gatewayPaymentId(), a.eventType(), a.gatewayEventId(), payment.id(), recorded, a.occurredAt(), now)) {
            return new Outcome(Result.DUPLICATE, null);
        }
        if (!allowed) {
            return new Outcome(Result.ANOMALY, mismatch != null ? mismatch : "status-" + payment.status());
        }
        Payment changed = (a.target() == Target.CONFIRM ? payments.confirm(payment.id(), a.occurredAt()) : payments.reject(payment.id(), a.occurredAt()))
                .orElseThrow(() -> new IllegalStateException("Платёж не перешёл в статус " + a.target().status()));
        transaction.publish(TOPIC, AGGREGATE, changed.orderId().toString(), a.target().event(), SCHEMA_VERSION, data(a, changed));
        return new Outcome(Result.APPLIED, null);
    }

    private static Map<String, Object> data(Application a, Payment payment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", payment.id().toString());
        data.put("orderId", payment.orderId().toString());
        if (a.target() == Target.CONFIRM) {
            Map<String, Object> money = new LinkedHashMap<>();
            money.put("amount", payment.amount());
            money.put("currency", payment.currency());
            data.put("amount", money);
            data.put("confirmedAt", Timestamps.format(a.occurredAt()));
        } else {
            data.put("reason", a.failureReason() == null ? "other" : a.failureReason());
        }
        return data;
    }

    /**
     * Записывает аномалию, не меняя статус: сумма не совпала, шлюз сообщил другое, чем мы ждали. Повтор той же пары запись не дублирует
     * и метрику не увеличивает.
     *
     * @return {@code true}, если запись сделана этим вызовом
     */
    public boolean recordUnapplied(Payment payment, String gatewayPaymentId, String eventType, String gatewayEventId, Instant occurredAt, String outcome,
                                   String reason) {
        boolean inserted = transaction.execute(() -> events.insertIfAbsent(gatewayPaymentId, eventType, gatewayEventId, payment.id(), outcome, occurredAt,
                clock.instant()));
        if (inserted) {
            meters.counter("payment_anomalies_total", "reason", reason).increment();
        }
        return inserted;
    }
}
