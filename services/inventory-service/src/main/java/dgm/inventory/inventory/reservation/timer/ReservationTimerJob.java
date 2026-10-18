package dgm.inventory.inventory.reservation.timer;

import dgm.inventory.inventory.reservation.service.ExpiryQueue;
import dgm.inventory.inventory.reservation.service.ReservationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Исполнитель таймера резервов (компонент {@code reservation-timer}, ADR-012, Р-141). Раз в секунду берёт из очереди Redis резервы,
 * срок которых наступил, и для каждого вызывает условное снятие в базе: сработало, значит резерв был активен, ключи вернулись в
 * свободные, события записаны; не сработало, значит его уже снял другой экземпляр, оплата или отмена. Участник убирается из очереди
 * в любом из этих двух случаев. Два экземпляра сервиса берут одну запись очереди одновременно, но условное обновление в базе
 * выигрывает ровно один (правило 15 руководства среза 2). Redis недоступен: проход не удался, резервы снимет сверка.
 */
@Component
public final class ReservationTimerJob {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationTimerJob.class);

    /** Сколько резервов берётся за проход: остальные дождутся следующей секунды. */
    static final int BATCH = 100;

    private final ExpiryQueue queue;
    private final ReservationService service;
    private final Clock clock;
    private final MeterRegistry meters;

    public ReservationTimerJob(ExpiryQueue queue, ReservationService service, Clock clock, MeterRegistry meters) {
        this.queue = queue;
        this.service = service;
        this.clock = clock;
        this.meters = meters;
    }

    /** Вход по расписанию; интервалы задаются ключами {@code dgm.reservation.*} (application.yml). */
    @Scheduled(fixedDelayString = "${dgm.reservation.timer-interval:PT1S}", initialDelayString = "${dgm.reservation.timer-initial-delay:PT5S}")
    void scheduled() {
        try {
            runPass();
        } catch (RuntimeException e) {
            meters.counter("reservation_timer_errors_total", "op", "poll").increment();
            LOG.warn("Проход таймера резервов не удался, повторится в следующий раз: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько резервов снято в этом проходе
     */
    public int runPass() {
        List<UUID> due = queue.due(clock.instant(), BATCH);
        int expired = 0;
        for (UUID reservationId : due) {
            if (service.expire(reservationId, ReservationService.Detected.TIMER) == ReservationService.Expiry.EXPIRED) {
                expired++;
            }
        }
        return expired;
    }
}
