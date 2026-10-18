package dgm.payment.payments.reconcile;

import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Сверка платежей со шлюзом по расписанию (компонент {@code reconciler}, ADR-015): раз в 5 минут платежи «создан» моложе 2 часов,
 * раз в час платежи от 2 до 24 часов; платежи старше 24 часов в сверку не берутся, они считаются и видны в метрике
 * {@code payments_stale_open} и в журнале (ежедневный отчёт администратору, FT-6.2). Интервалы задают ключи
 * {@code dgm.payment.reconcile.*}. Потерянный вебхук подхватывается первым же проходом, пока платёж молод.
 */
public final class PaymentReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentReconciler.class);

    private final PaymentRepository payments;
    private final PaymentReconciliation reconciliation;
    private final ReconcileSettings settings;
    private final Clock clock;
    private final AtomicInteger stale;

    public PaymentReconciler(PaymentRepository payments, PaymentReconciliation reconciliation, ReconcileSettings settings, Clock clock, MeterRegistry meters) {
        this.payments = payments;
        this.reconciliation = reconciliation;
        this.settings = settings;
        this.clock = clock;
        this.stale = meters.gauge("payments_stale_open", new AtomicInteger());
    }

    /** Молодые платежи: до {@code youngAge}. */
    @Scheduled(fixedDelayString = "${dgm.payment.reconcile.young-interval:PT5M}", initialDelayString = "${dgm.payment.reconcile.initial-delay:PT30S}")
    void youngScheduled() {
        guarded("молодых", this::runYoungPass);
    }

    /** Платежи от {@code youngAge} до {@code maxAge} и подсчёт старше {@code maxAge}. */
    @Scheduled(fixedDelayString = "${dgm.payment.reconcile.old-interval:PT1H}", initialDelayString = "${dgm.payment.reconcile.old-initial-delay:PT2M}")
    void oldScheduled() {
        guarded("старых", this::runOldPass);
    }

    private void guarded(String what, Runnable pass) {
        try {
            pass.run();
        } catch (RuntimeException e) {
            LOG.error("Сверка {} платежей завершилась ошибкой, повторится в следующий раз", what, e);
        }
    }

    /**
     * Один проход по молодым платежам (и для тестов).
     *
     * @return сколько платежей изменило статус
     */
    public int runYoungPass() {
        Instant now = clock.instant();
        return pass(payments.openBetween(now.minus(settings.youngAge()), now, settings.batch()));
    }

    /**
     * Один проход по платежам среднего возраста (и для тестов).
     *
     * @return сколько платежей изменило статус
     */
    public int runOldPass() {
        Instant now = clock.instant();
        int changed = pass(payments.openBetween(now.minus(settings.maxAge()), now.minus(settings.youngAge()), settings.batch()));
        int old = payments.openBetween(Instant.EPOCH, now.minus(settings.maxAge()), settings.batch()).size();
        stale.set(old);
        if (old > 0) {
            LOG.warn("Платежей «создан» старше {} часов, которые сверка больше не берёт: {} (разбор администратором)", settings.maxAge().toHours(), old);
        }
        return changed;
    }

    private int pass(List<Payment> batch) {
        int changed = 0;
        for (Payment payment : batch) {
            PaymentReconciliation.Result result = reconciliation.reconcile(payment);
            if (result == PaymentReconciliation.Result.CONFIRMED || result == PaymentReconciliation.Result.REJECTED) {
                changed++;
                LOG.warn("Сверка исправила платёж {}: {}. Уведомление шлюза не дошло", payment.id(), result);
            }
        }
        return changed;
    }
}
