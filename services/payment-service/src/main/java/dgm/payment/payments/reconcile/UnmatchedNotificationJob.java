package dgm.payment.payments.reconcile;

import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.repository.PaymentRepository;
import dgm.payment.payments.repository.UnmatchedNotificationRepository;
import dgm.payment.payments.repository.UnmatchedNotificationRepository.Unmatched;
import dgm.payment.payments.service.PaymentOutcomes;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Сопоставление несопоставленных уведомлений шлюза с платежами (ADR-015, шаг 3): раз в 10 секунд каждое уведомление ищет свой платёж.
 * Платёж появился (уведомление пришло раньше, чем открылась сессия) или получил внешний идентификатор: платёж сверяется со шлюзом и
 * исход применяется общим кодом. Тело уведомления не хранится, поэтому исходом управляет статус у шлюза, а не текст уведомления.
 * Уведомление, которое не сопоставилось за {@code alertAfter} (час), даёт оповещение: запись в журнал уровня ERROR и метрику
 * {@code payment_unmatched_alerts_total}; админский канал уведомлений появится вместе с обработчиком уведомлений платформы.
 */
public final class UnmatchedNotificationJob {

    private static final Logger LOG = LoggerFactory.getLogger(UnmatchedNotificationJob.class);

    private final UnmatchedNotificationRepository unmatched;
    private final PaymentRepository payments;
    private final PaymentReconciliation reconciliation;
    private final PaymentOutcomes outcomes;
    private final ReconcileSettings settings;
    private final Clock clock;
    private final MeterRegistry meters;

    public UnmatchedNotificationJob(UnmatchedNotificationRepository unmatched, PaymentRepository payments, PaymentReconciliation reconciliation,
                                    PaymentOutcomes outcomes, ReconcileSettings settings, Clock clock, MeterRegistry meters) {
        this.unmatched = unmatched;
        this.payments = payments;
        this.reconciliation = reconciliation;
        this.outcomes = outcomes;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${dgm.payment.reconcile.unmatched-interval:PT10S}", initialDelayString = "${dgm.payment.reconcile.unmatched-initial-delay:PT15S}")
    void scheduled() {
        try {
            runPass();
        } catch (RuntimeException e) {
            LOG.error("Сопоставление уведомлений завершилось ошибкой, повторится в следующий раз", e);
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько уведомлений сопоставлено
     */
    public int runPass() {
        int matched = 0;
        for (Unmatched notification : unmatched.pending(settings.batch())) {
            if (match(notification)) {
                matched++;
            }
        }
        Instant now = clock.instant();
        for (Unmatched overdue : unmatched.overdueWithoutAlert(now.minus(settings.alertAfter()), settings.batch())) {
            LOG.error("Уведомление шлюза {} по платежу {} не сопоставлено больше {} минут: нужен разбор администратором", overdue.gatewayEventId(),
                    overdue.gatewayPaymentId(), settings.alertAfter().toMinutes());
            meters.counter("payment_unmatched_alerts_total").increment();
            unmatched.markAlerted(overdue.id(), now);
        }
        return matched;
    }

    private boolean match(Unmatched notification) {
        Payment payment = payments.findByGatewayPaymentId(notification.gatewayPaymentId()).orElse(null);
        if (payment == null && notification.orderId() != null) {
            Payment byOrder = payments.findByOrder(notification.orderId()).orElse(null);
            if (byOrder != null && byOrder.gatewayPaymentId() == null) {
                payment = payments.adoptGatewayPaymentId(byOrder.id(), notification.gatewayPaymentId()).orElse(null);
            } else if (byOrder != null) {
                // У платежа заказа другой внешний идентификатор: уведомление чужое, ждать нечего
                outcomes.recordUnapplied(byOrder, notification.gatewayPaymentId(), notification.eventType(), notification.gatewayEventId(),
                        notification.occurredAt(), PaymentEventRepository.ANOMALY, "gateway-id-mismatch");
                unmatched.markMatched(notification.id(), clock.instant());
                meters.counter("payment_unmatched_total", "result", "mismatch").increment();
                return true;
            }
        }
        if (payment == null) {
            return false;
        }
        PaymentReconciliation.Result result = reconciliation.reconcile(payment);
        if (result == PaymentReconciliation.Result.UNAVAILABLE) {
            return false;
        }
        unmatched.markMatched(notification.id(), clock.instant());
        meters.counter("payment_unmatched_total", "result", "matched").increment();
        return true;
    }
}
