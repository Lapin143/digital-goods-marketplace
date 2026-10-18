package dgm.inventory.inventory.reservation.timer;

import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.inventory.inventory.reservation.domain.ReservationSettings;
import dgm.inventory.inventory.reservation.repository.ReservationRepository;
import dgm.inventory.inventory.reservation.service.ExpiryQueue;
import dgm.inventory.inventory.reservation.service.ReservationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Сверка резервов (компонент {@code reservation-reconciler}, ADR-012): страховка на случай, если таймер Redis потерян. Раз в минуту
 * находит по базе активные резервы, срок которых вышел больше чем на {@code dgm.reservation.reconcile-grace-seconds} (по умолчанию 30
 * секунд), и снимает их тем же путём, что таймер, с пометкой {@code expiredBy = reconciliation}. При старте сервиса заново ставит
 * в очередь сроки всех активных резервов: потерянный Redis восстанавливается пересчётом по базе, источник истины это PostgreSQL.
 * Метрика {@code reservations_reconciled_total} считает резервы, снятые сверкой.
 */
@Component
public final class ReservationReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationReconciler.class);

    /** Сколько резервов обрабатывается за проход и за выборку при перезагрузке таймеров. */
    static final int BATCH = 200;

    private final ReservationRepository reservations;
    private final ReservationService service;
    private final ExpiryQueue queue;
    private final ReservationSettings settings;
    private final Clock clock;
    private final MeterRegistry meters;

    public ReservationReconciler(ReservationRepository reservations, ReservationService service, ExpiryQueue queue, ReservationSettings settings,
                                 Clock clock, MeterRegistry meters) {
        this.reservations = reservations;
        this.service = service;
        this.queue = queue;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    /** Вход по расписанию; интервалы задаются ключами {@code dgm.reservation.*} (application.yml). */
    @Scheduled(fixedDelayString = "${dgm.reservation.reconcile-interval:PT1M}", initialDelayString = "${dgm.reservation.reconcile-initial-delay:PT20S}")
    void scheduled() {
        try {
            runPass();
        } catch (RuntimeException e) {
            LOG.error("Сверка резервов завершилась ошибкой, повторится в следующий раз", e);
        }
    }

    /** При старте: сроки активных резервов заново в очередь (идемпотентно: {@code ZADD} обновляет оценку). Redis недоступен: не страшно, есть сверка. */
    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        try {
            int count = reloadTimers();
            LOG.info("Таймеры резервов загружены из базы: {} шт.", count);
        } catch (RuntimeException e) {
            meters.counter("reservation_timer_errors_total", "op", "reload").increment();
            LOG.warn("Таймеры резервов не загружены, резервы снимет сверка: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько резервов снято сверкой
     */
    public int runPass() {
        List<UUID> overdue = reservations.overdue(clock.instant().minus(settings.reconcileGrace()), BATCH);
        int expired = 0;
        for (UUID reservationId : overdue) {
            if (service.expire(reservationId, ReservationService.Detected.RECONCILIATION) == ReservationService.Expiry.EXPIRED) {
                expired++;
            }
        }
        if (expired > 0) {
            meters.counter("reservations_reconciled_total").increment(expired);
            LOG.warn("Сверка сняла резервы, которых не снял таймер: {} шт.", expired);
        }
        return expired;
    }

    /**
     * Ставит в очередь сроки всех активных резервов.
     *
     * @return сколько резервов поставлено
     */
    public int reloadTimers() {
        int total = 0;
        UUID after = new UUID(0L, 0L);
        while (true) {
            List<Reservation> batch = reservations.activeAfter(after, BATCH);
            if (batch.isEmpty()) {
                return total;
            }
            for (Reservation reservation : batch) {
                queue.schedule(reservation.id(), reservation.expiresAt());
            }
            total += batch.size();
            after = batch.get(batch.size() - 1).id();
        }
    }
}
