package dgm.payment.payments.reconcile;

import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.gateway.GatewayPaymentState;
import dgm.payment.payments.gateway.GatewayRejectedException;
import dgm.payment.payments.gateway.GatewayUnavailableException;
import dgm.payment.payments.gateway.PaymentGateway;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.service.PaymentOutcomes;
import dgm.payment.payments.service.PaymentOutcomes.Application;
import dgm.payment.payments.service.PaymentOutcomes.Target;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Сверка одного платежа со шлюзом (ADR-015, «сверка как страховка от потерянных уведомлений»): запрашивает статус и лечит
 * расхождение тем же путём, что вебхук ({@link PaymentOutcomes}, тот же SM-02 и тот же Outbox). Типы событий для дедупликации здесь
 * свои ({@code reconcile.paid}, {@code reconcile.declined}), поэтому аномалия вебхука не закрывает сверке путь, а повторная сверка
 * платежа ничего не меняет: уже применённый исход это повтор.
 *
 * <p>Шлюз сообщает правду о деньгах, поэтому уведомления не нужно: оплачен у шлюза значит {@code created → confirmed}, отказан значит
 * {@code created → rejected}. Сумма и валюта у шлюза обязаны совпадать с платёжными, иначе статус не меняется и платёж идёт на разбор.
 */
public final class PaymentReconciliation {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentReconciliation.class);

    static final String PAID = "reconcile.paid";
    static final String DECLINED = "reconcile.declined";
    static final String ANOMALY = "reconcile.anomaly";
    static final String EVENT_ID = "reconciliation";

    /** Итог сверки одного платежа. */
    public enum Result {
        /** Платёж оплачен у шлюза, статус изменён на «подтверждён». */
        CONFIRMED,
        /** Платёж отклонён у шлюза, статус изменён на «отклонён». */
        REJECTED,
        /** Менять нечего: платёж ждёт оплаты или уже обработан. */
        UNCHANGED,
        /** Шлюз не ответил, платёж подберёт следующий проход. */
        UNAVAILABLE,
        /** Шлюз сообщил то, чего быть не может, или сумма не совпала: статус не изменён, разбор вручную. */
        ANOMALY
    }

    private final PaymentGateway gateway;
    private final PaymentOutcomes outcomes;
    private final Clock clock;
    private final MeterRegistry meters;

    public PaymentReconciliation(PaymentGateway gateway, PaymentOutcomes outcomes, Clock clock, MeterRegistry meters) {
        this.gateway = gateway;
        this.outcomes = outcomes;
        this.clock = clock;
        this.meters = meters;
    }

    public Result reconcile(Payment payment) {
        Result result = run(payment);
        meters.counter("payment_reconciled_total", "result", result.name().toLowerCase(Locale.ROOT)).increment();
        return result;
    }

    private Result run(Payment payment) {
        if (!payment.isCreated() || payment.gatewayPaymentId() == null) {
            return Result.UNCHANGED;
        }
        String gatewayId = payment.gatewayPaymentId();
        GatewayPaymentState state;
        try {
            state = gateway.lookupStatus(gatewayId);
        } catch (GatewayUnavailableException e) {
            LOG.warn("Сверка: шлюз не ответил по платежу {}, повторится в следующем проходе", payment.id());
            return Result.UNAVAILABLE;
        } catch (GatewayRejectedException e) {
            return anomaly(payment, "reconcile-gateway-unknown");
        }
        Instant now = clock.instant();
        return switch (state.status()) {
            case PAID -> {
                if (!state.sameMoney(payment.amount(), payment.currency())) {
                    outcomes.recordUnapplied(payment, gatewayId, PAID, EVENT_ID, now, PaymentEventRepository.AMOUNT_MISMATCH, "reconcile-amount-mismatch");
                    yield Result.ANOMALY;
                }
                yield of(outcomes.apply(new Application(gatewayId, PAID, EVENT_ID, Target.CONFIRM, payment.orderId(), now, null)), Result.CONFIRMED);
            }
            case DECLINED -> of(outcomes.apply(new Application(gatewayId, DECLINED, EVENT_ID, Target.REJECT, payment.orderId(), now, "declined")),
                    Result.REJECTED);
            // Ждёт оплаты или срок сессии у шлюза вышел: платёж остаётся «создан» на случай поздней оплаты (SM-01/T6)
            case PENDING, EXPIRED -> Result.UNCHANGED;
            // У платежа «создан» не бывает возврата: шлюз и мы расходимся
            case REFUNDING, REFUNDED, UNKNOWN -> anomaly(payment, "reconcile-status-" + state.status().name().toLowerCase(Locale.ROOT));
        };
    }

    /** Применено: итог по исходу; уже применённое другим путём: без изменений; не подошло платежу: аномалия (запись сделана общим кодом). */
    private static Result of(PaymentOutcomes.Result applied, Result onApplied) {
        return switch (applied) {
            case APPLIED -> onApplied;
            case DUPLICATE -> Result.UNCHANGED;
            case ANOMALY -> Result.ANOMALY;
        };
    }

    private Result anomaly(Payment payment, String reason) {
        outcomes.recordUnapplied(payment, payment.gatewayPaymentId(), ANOMALY, EVENT_ID, clock.instant(), PaymentEventRepository.ANOMALY, reason);
        return Result.ANOMALY;
    }
}
