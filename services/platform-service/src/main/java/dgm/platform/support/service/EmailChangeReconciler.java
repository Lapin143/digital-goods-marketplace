package dgm.platform.support.service;

import dgm.kit.outbox.OutboxRelay;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Сверка смен e-mail по обращению (компонент {@code ticket-service}, SEQ-04, раздел 7, Р-169): раз в полминуты находит смены в состоянии «адрес
 * подтверждён, не завершён», которые не менялись дольше минуты, и доводит их до конца ({@link EmailChangeService#finishStuck}). Так закрыты падение
 * платформы между Keycloak и транзакцией, недоступность identity в момент ввода кода из письма и сбой между шагами завершения; коды покупателя при этом
 * заново не запрашиваются. Прежнюю ветку очистки прежних адресов e-mail ведёт {@code IdentityReconciler}: эта таблица принадлежит другому модулю.
 *
 * <p>Лидер тот же, что у публикатора Outbox ({@link OutboxRelay#isLeader()}); проход идемпотентен, поэтому смена лидера посреди прохода вреда не делает.
 */
public final class EmailChangeReconciler {

    /** Сколько смен берётся за проход: остальные уйдут на следующий. */
    static final int BATCH = 50;
    private static final Logger LOG = LoggerFactory.getLogger(EmailChangeReconciler.class);

    private final EmailChangeService service;
    private final ObjectProvider<OutboxRelay> relay;
    private final Duration stuckAfter;
    private final Clock clock;

    /**
     * @param stuckAfter сколько смена должна простоять неизменной, чтобы сверка за неё взялась: запрос покупателя успевает завершить смену сам
     */
    public EmailChangeReconciler(EmailChangeService service, ObjectProvider<OutboxRelay> relay, Duration stuckAfter, Clock clock) {
        this.service = service;
        this.relay = relay;
        this.stuckAfter = stuckAfter;
        this.clock = clock;
    }

    /** Вход по расписанию: проход делает только лидер. Интервал и первая пауза задаются ключами {@code dgm.support.*} (application.yml). */
    @Scheduled(fixedDelayString = "${dgm.support.email-change-reconcile-interval:PT30S}", initialDelayString = "${dgm.support.email-change-reconcile-initial-delay:PT30S}")
    void scheduled() {
        OutboxRelay publisher = relay.getIfAvailable();
        if (publisher != null && !publisher.isLeader()) {
            return;
        }
        try {
            int finished = runPass();
            if (finished > 0) {
                LOG.info("Сверка смен e-mail: завершено {}", finished);
            }
        } catch (RuntimeException e) {
            LOG.error("Сверка смен e-mail завершилась ошибкой: {}", e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Один проход без проверки лидерства (для тестов и ручного запуска).
     *
     * @return сколько смен завершено
     */
    public int runPass() {
        Instant before = clock.instant().minus(stuckAfter);
        return service.finishStuck(before, BATCH);
    }
}
