package dgm.payment.payments.service;

import dgm.kit.id.Uuids;
import dgm.kit.time.Timestamps;
import dgm.payment.payments.domain.RefundAttempt;
import dgm.payment.payments.domain.RefundPayment;
import dgm.payment.payments.domain.RefundSchedule;
import dgm.payment.payments.gateway.GatewayRefund;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.repository.RefundRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Порядок возврата денег (ADR-015, «Возврат», SM-02/T4, INV-15): запрос по событию {@code order.refund-requested}, итог попытки у
 * шлюза, подтверждение возврата от шлюза и отметка администратора. Все изменения идут под блокировкой строки платежа ({@code FOR UPDATE}),
 * поэтому событие-команда, исполнитель, вебхук и администратор выстраиваются друг за другом, а возврат остаётся один.
 *
 * <p>Вызовов шлюза здесь нет (правило 9): их делает исполнитель до транзакции. Событие {@code payment.refunded} пишется в Outbox той же
 * транзакцией, что и смена статуса (ADR-005). {@link #request} вызывается внутри транзакции потребителя события, остальные методы открывают
 * свою короткую транзакцию.
 */
public final class RefundOutcomes {

    /** События платежей уходят в ту же тему, что подтверждение и отказ. */
    public static final String TOPIC = PaymentOutcomes.TOPIC;
    public static final String EVENT_REFUNDED = "payment.refunded";
    public static final String EVENT_ESCALATED = "payment.refund-escalated";
    private static final String AGGREGATE = "Payment";
    private static final int SCHEMA_VERSION = 1;

    /** Что сделал {@link #request}. */
    public enum Requested {
        /** Возврат запрошен, первая попытка назначена. */
        STARTED,
        /** Платёж уже возвращён: повтор команды ничего не делает. */
        ALREADY_REFUNDED,
        /** Возврат уже запрошен и выполняется: второй не создаётся (INV-15, TC-038). */
        IN_PROGRESS,
        /** Автоматические попытки исчерпаны, возврат ждёт администратора. */
        IN_QUEUE,
        /** Платежа по заказу нет: команда не к нашим данным. */
        NO_PAYMENT,
        /** Платёж не подтверждён (создан или отклонён): возвращать нечего. */
        NOT_PAID,
        /** Сумма или валюта команды не равна платёжной: возврат всегда полный. */
        AMOUNT_MISMATCH
    }

    /** Итог неудачной попытки. */
    public enum Failure {
        /** Назначена следующая попытка. */
        RETRY,
        /** Попытки исчерпаны, возврат передан администратору. */
        ESCALATED,
        /** Попытку уже закрыл другой путь (вебхук, другой экземпляр): ничего не меняется. */
        SUPERSEDED
    }

    /** Итог подтверждения возврата от шлюза. */
    public enum Confirmed {
        APPLIED,
        /** Платёж уже возвращён или такая пара «платёж, тип» уже обработана. */
        DUPLICATE,
        /** Уведомление не подходит платежу: статус не изменён, разбор вручную. */
        ANOMALY
    }

    /**
     * Команда возврата.
     *
     * @param orderId  заказ
     * @param amount   сумма в копейках, равна сумме заказа
     * @param currency валюта
     * @param reason   причина из {@link RefundPayment#REASONS}
     */
    public record Request(UUID orderId, long amount, String currency, String reason) {
    }

    /**
     * Подтверждение возврата шлюзом: уведомление {@code refund.completed} или результат сверки.
     *
     * @param gatewayPaymentId внешний идентификатор платежа
     * @param eventType        тип события для дедупликации (у сверки свой, чтобы аномалия вебхука не закрыла ей путь)
     * @param gatewayEventId   идентификатор уведомления у шлюза, для разбора
     * @param orderId          заказ платежа
     * @param gatewayRefundId  внешний идентификатор возврата или {@code null}, если шлюз его не назвал
     * @param occurredAt       время события по данным шлюза; для сверки время запроса
     */
    public record Confirmation(String gatewayPaymentId, String eventType, String gatewayEventId, UUID orderId, String gatewayRefundId,
                               Instant occurredAt) {
    }

    private final RefundRepository refunds;
    private final PaymentEventRepository events;
    private final PaymentTransaction transaction;
    private final RefundSchedule schedule;
    private final Clock clock;
    private final MeterRegistry meters;

    public RefundOutcomes(RefundRepository refunds, PaymentEventRepository events, PaymentTransaction transaction, RefundSchedule schedule, Clock clock,
                          MeterRegistry meters) {
        this.refunds = refunds;
        this.events = events;
        this.transaction = transaction;
        this.schedule = schedule;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Запрашивает возврат по заказу: запоминает причину и назначает первую попытку. Повторная команда второго возврата не создаёт. Вызывается
     * внутри транзакции потребителя события.
     */
    public Requested request(Request request) {
        RefundPayment payment = refunds.lockByOrder(request.orderId()).orElse(null);
        Requested rejected = payment == null ? Requested.NO_PAYMENT : refusal(payment, request);
        if (rejected != null) {
            count("command-" + rejected.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
            return rejected;
        }
        Instant now = clock.instant();
        refunds.recordReason(payment.id(), request.reason());
        if (!refunds.insertAttempt(RefundAttempt.scheduled(Uuids.v7(clock), payment.id(), 1, schedule.dueAt(1, now)), now)) {
            return Requested.IN_PROGRESS;
        }
        count("requested");
        return Requested.STARTED;
    }

    private static Requested refusal(RefundPayment payment, Request request) {
        if (payment.refunded()) {
            return Requested.ALREADY_REFUNDED;
        }
        if (!payment.refundable()) {
            return Requested.NOT_PAID;
        }
        if (payment.amount() != request.amount() || !payment.currency().equals(request.currency())) {
            return Requested.AMOUNT_MISMATCH;
        }
        if (payment.inAdminQueue()) {
            return Requested.IN_QUEUE;
        }
        return payment.refundRequested() ? Requested.IN_PROGRESS : null;
    }

    /**
     * Шлюз принял возврат. {@code instant}: платёж становится «возвращён», пишется {@code payment.refunded}. Иначе попытка ждёт
     * подтверждения ({@code processing}), внешний идентификатор возврата записан.
     */
    public void accepted(RefundAttempt attempt, GatewayRefund refund) {
        transaction.execute(() -> {
            RefundPayment payment = refunds.lockById(attempt.paymentId()).orElseThrow(() -> new IllegalStateException("Платёж попытки возврата не найден"));
            if (payment.refunded()) {
                // вебхук опередил исполнителя и сам закрыл попытку
                return null;
            }
            Instant now = clock.instant();
            if (refund.completed()) {
                refunds.closeOpenAttempts(payment.id(), now);
                markRefunded(payment, refund.id(), now, false);
                count("completed");
            } else {
                refunds.finishAttempt(attempt.id(), RefundAttempt.PROCESSING, null, now);
                refunds.attachRefundId(payment.id(), refund.id());
                count("processing");
            }
            return null;
        });
    }

    /**
     * Попытка не удалась. Назначает следующую по расписанию или, если попытки кончились, передаёт возврат администратору и пишет
     * {@code payment.refund-escalated}.
     *
     * @param errorCode короткий код ошибки шлюза для {@code refund_attempt.error_code}: строчные буквы, цифры, {@code _ . -}
     */
    public Failure failed(RefundAttempt attempt, String errorCode) {
        Failure failure = transaction.execute(() -> {
            RefundPayment payment = refunds.lockById(attempt.paymentId()).orElseThrow(() -> new IllegalStateException("Платёж попытки возврата не найден"));
            Instant now = clock.instant();
            if (!payment.refundable() || payment.inAdminQueue() || !refunds.finishAttempt(attempt.id(), RefundAttempt.FAILED, errorCode, now)) {
                return Failure.SUPERSEDED;
            }
            if (attempt.attemptNo() < schedule.maxAttempts()) {
                int next = attempt.attemptNo() + 1;
                refunds.insertAttempt(RefundAttempt.scheduled(Uuids.v7(clock), payment.id(), next, schedule.dueAt(next, now)), now);
                return Failure.RETRY;
            }
            if (refunds.escalate(payment.id(), now)) {
                transaction.publish(TOPIC, AGGREGATE, payment.orderId().toString(), EVENT_ESCALATED, SCHEMA_VERSION, escalated(payment));
            }
            return Failure.ESCALATED;
        });
        count(failure == Failure.SUPERSEDED ? "superseded" : failure == Failure.RETRY ? "failed" : "escalated");
        return failure;
    }

    private Map<String, Object> escalated(RefundPayment payment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", payment.id().toString());
        data.put("orderId", payment.orderId().toString());
        data.put("amount", money(payment));
        data.put("attempts", Math.max(1, refunds.attemptCount(payment.id())));
        return data;
    }

    /**
     * Шлюз сообщил, что деньги возвращены: уведомление {@code refund.completed} или сверка. Одна транзакция: запись обработанного
     * уведомления (дедупликация, INV-14), закрытие попыток, смена статуса и {@code payment.refunded} в Outbox.
     */
    public Confirmed confirmed(Confirmation confirmation) {
        Outcome outcome = transaction.execute(() -> confirmedLocked(confirmation));
        if (outcome.anomalyReason != null) {
            meters.counter("payment_anomalies_total", "reason", outcome.anomalyReason).increment();
        }
        count(outcome.result == Confirmed.APPLIED ? "confirmed" : outcome.result == Confirmed.DUPLICATE ? "confirmed-duplicate" : "confirmed-anomaly");
        return outcome.result;
    }

    private record Outcome(Confirmed result, String anomalyReason) {
    }

    private Outcome confirmedLocked(Confirmation c) {
        RefundPayment payment = refunds.lockByOrder(c.orderId()).orElse(null);
        if (payment == null) {
            return new Outcome(Confirmed.ANOMALY, "refund-unknown-payment");
        }
        // Тот же исход уже применён другим путём (отметка администратора, ответ шлюза на возврат, вебхук): это повтор, а не аномалия
        if (payment.refunded()) {
            return new Outcome(Confirmed.DUPLICATE, null);
        }
        String reason = mismatch(payment, c);
        Instant now = clock.instant();
        String recorded = reason == null ? PaymentEventRepository.APPLIED : PaymentEventRepository.ANOMALY;
        if (!events.insertIfAbsent(c.gatewayPaymentId(), c.eventType(), c.gatewayEventId(), payment.id(), recorded, c.occurredAt(), now)) {
            return new Outcome(Confirmed.DUPLICATE, null);
        }
        if (reason != null) {
            return new Outcome(Confirmed.ANOMALY, reason);
        }
        refunds.closeOpenAttempts(payment.id(), now);
        markRefunded(payment, c.gatewayRefundId(), c.occurredAt(), false);
        return new Outcome(Confirmed.APPLIED, null);
    }

    private static String mismatch(RefundPayment payment, Confirmation c) {
        if (!c.gatewayPaymentId().equals(payment.gatewayPaymentId())) {
            return "gateway-id-mismatch";
        }
        if (!payment.refundable()) {
            return "status-" + payment.status();
        }
        if (!payment.refundRequested()) {
            return "refund-not-requested";
        }
        if (payment.gatewayRefundId() != null && c.gatewayRefundId() != null && !payment.gatewayRefundId().equals(c.gatewayRefundId())) {
            return "gateway-refund-mismatch";
        }
        return null;
    }

    /**
     * {@code confirmed → refunded} и событие {@code payment.refunded}. Вызывается внутри транзакции под блокировкой строки платежа; причина
     * возврата у платежа обязательна (ограничение базы {@code ck_payment_refunded}).
     *
     * @param manual {@code true}, если возврат отметил администратор вручную
     */
    public RefundPayment markRefunded(RefundPayment payment, String gatewayRefundId, Instant at, boolean manual) {
        if (payment.refundReason() == null) {
            throw new IllegalStateException("У платежа " + payment.id() + " нет причины возврата");
        }
        RefundPayment changed = refunds.markRefunded(payment.id(), gatewayRefundId, at)
                .orElseThrow(() -> new IllegalStateException("Платёж не перешёл в статус refunded"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", changed.id().toString());
        data.put("orderId", changed.orderId().toString());
        data.put("amount", money(changed));
        data.put("refundReason", payment.refundReason());
        data.put("manual", manual);
        data.put("refundedAt", Timestamps.format(at));
        transaction.publish(TOPIC, AGGREGATE, changed.orderId().toString(), EVENT_REFUNDED, SCHEMA_VERSION, data);
        return changed;
    }

    private static Map<String, Object> money(RefundPayment payment) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", payment.amount());
        money.put("currency", payment.currency());
        return money;
    }

    private void count(String result) {
        meters.counter("payment_refunds_total", "result", result).increment();
    }
}
