package dgm.payment.payments.refund;

import dgm.payment.payments.domain.RefundPayment;
import dgm.payment.payments.gateway.GatewayPaymentState;
import dgm.payment.payments.gateway.GatewayRejectedException;
import dgm.payment.payments.gateway.GatewayStatus;
import dgm.payment.payments.gateway.GatewayUnavailableException;
import dgm.payment.payments.gateway.PaymentGateway;
import dgm.payment.payments.repository.RefundRepository;
import dgm.payment.payments.service.RefundOutcomes;
import dgm.payment.payments.service.RefundOutcomes.Confirmation;
import dgm.payment.payments.service.RefundOutcomes.Confirmed;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Сверка возвратов со шлюзом (ADR-015, «Возврат»): шлюз принял возврат («в обработке»), а уведомление {@code refund.completed} не пришло.
 * Раз в несколько минут сверка спрашивает шлюз о платежах, чей возврат принят давно, и если деньги возвращены, применяет итог тем же
 * общим кодом, что вебхук ({@link RefundOutcomes#confirmed}), под своим типом события {@code reconcile.refunded}: аномалия вебхука не
 * закрывает сверке путь. Шлюз не ответил или возврат ещё выполняется: платёж остаётся и проверится в следующий раз.
 */
public final class RefundReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(RefundReconciler.class);

    /** Тип события сверки в {@code payment_event}. */
    public static final String EVENT_TYPE = "reconcile.refunded";

    private final RefundRepository refunds;
    private final PaymentGateway gateway;
    private final RefundOutcomes outcomes;
    private final RefundSettings settings;
    private final Clock clock;

    public RefundReconciler(RefundRepository refunds, PaymentGateway gateway, RefundOutcomes outcomes, RefundSettings settings, Clock clock) {
        this.refunds = refunds;
        this.gateway = gateway;
        this.outcomes = outcomes;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${dgm.payment.refund.reconcile-interval:PT5M}", initialDelayString = "${dgm.payment.refund.reconcile-initial-delay:PT1M}")
    void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.error("Сверка возвратов завершилась ошибкой, повторится в следующий раз", e);
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько возвратов подтвердила сверка
     */
    public int runOnce() {
        Instant now = clock.instant();
        List<RefundPayment> waiting = refunds.awaitingConfirmation(now.minus(settings.processingAge()), 100);
        int confirmed = 0;
        for (RefundPayment payment : waiting) {
            if (reconcile(payment, now)) {
                confirmed++;
                LOG.warn("Сверка подтвердила возврат платежа {}: уведомление шлюза о возврате не дошло", payment.id());
            }
        }
        return confirmed;
    }

    private boolean reconcile(RefundPayment payment, Instant now) {
        GatewayPaymentState state;
        try {
            state = gateway.lookupStatus(payment.gatewayPaymentId());
        } catch (GatewayUnavailableException | GatewayRejectedException e) {
            LOG.warn("Шлюз не сообщил статус платежа {} при сверке возврата: {}", payment.id(), e.getMessage());
            return false;
        }
        if (state.status() != GatewayStatus.REFUNDED) {
            return false;
        }
        return outcomes.confirmed(new Confirmation(payment.gatewayPaymentId(), EVENT_TYPE, "reconcile", payment.orderId(), payment.gatewayRefundId(), now))
                == Confirmed.APPLIED;
    }
}
