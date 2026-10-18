package dgm.payment.payments.refund;

import dgm.payment.payments.domain.RefundAttempt;
import dgm.payment.payments.domain.RefundPayment;
import dgm.payment.payments.gateway.GatewayRefund;
import dgm.payment.payments.gateway.GatewayRejectedException;
import dgm.payment.payments.gateway.GatewayUnavailableException;
import dgm.payment.payments.gateway.PaymentGateway;
import dgm.payment.payments.repository.RefundRepository;
import dgm.payment.payments.service.RefundOutcomes;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Исполнитель возвратов (ADR-015, ADR-011): раз в 5 секунд берёт в аренду попытки, срок которых наступил ({@code FOR UPDATE SKIP LOCKED},
 * два экземпляра сервиса одну попытку не возьмут), вызывает шлюз <b>вне транзакции</b> (правило 9) и коротко записывает итог. Ключ
 * идемпотентности шлюза {@code refund-<paymentId>} один на все попытки, поэтому повтор после потерянного ответа или упавшего процесса
 * возврат не удваивает (INV-15). Аренда истекла, а итог не записан: попытка наступает снова.
 *
 * <p>Любой отказ шлюза (ответа нет, 4xx, предохранитель) это неудачная попытка; пятая неудача передаёт возврат администратору. Ошибка
 * записи итога попытку не закрывает: её подберёт следующий проход после аренды.
 */
public final class RefundExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(RefundExecutor.class);

    private final RefundRepository refunds;
    private final PaymentGateway gateway;
    private final RefundOutcomes outcomes;
    private final RefundSettings settings;
    private final Clock clock;

    public RefundExecutor(RefundRepository refunds, PaymentGateway gateway, RefundOutcomes outcomes, RefundSettings settings, Clock clock) {
        this.refunds = refunds;
        this.gateway = gateway;
        this.outcomes = outcomes;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${dgm.payment.refund.interval:PT5S}", initialDelayString = "${dgm.payment.refund.initial-delay:PT5S}")
    void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.error("Проход исполнителя возвратов завершился ошибкой, повторится в следующий раз", e);
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько попыток взято в работу
     */
    public int runOnce() {
        List<RefundAttempt> due = refunds.claimDue(clock.instant(), settings.lease(), settings.batch());
        for (RefundAttempt attempt : due) {
            try {
                execute(attempt);
            } catch (RuntimeException e) {
                // итог не записан: попытка остаётся «занята» и наступит снова, когда истечёт аренда
                LOG.error("Попытка возврата {} по платежу {} не завершена, повторится после аренды", attempt.id(), attempt.paymentId(), e);
            }
        }
        return due.size();
    }

    private void execute(RefundAttempt attempt) {
        RefundPayment payment = refunds.findById(attempt.paymentId()).orElseThrow(() -> new IllegalStateException("Платёж попытки возврата не найден"));
        if (!payment.refundable()) {
            // деньги уже вернул другой путь (вебхук, администратор): попытку закрыл он
            return;
        }
        GatewayRefund refund;
        try {
            refund = gateway.createRefund(payment.id(), payment.gatewayPaymentId(), payment.amount(), payment.currency());
        } catch (GatewayRejectedException e) {
            LOG.warn("Шлюз не принял возврат платежа {} (попытка {}): код {}", payment.id(), attempt.attemptNo(), e.status());
            outcomes.failed(attempt, "gateway_rejected_" + e.status());
            return;
        } catch (GatewayUnavailableException e) {
            LOG.warn("Шлюз не ответил на возврат платежа {} (попытка {}): {}", payment.id(), attempt.attemptNo(), e.getMessage());
            outcomes.failed(attempt, "gateway_unavailable");
            return;
        }
        outcomes.accepted(attempt, refund);
    }
}
