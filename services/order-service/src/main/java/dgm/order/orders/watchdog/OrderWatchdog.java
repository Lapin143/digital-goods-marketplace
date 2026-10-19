package dgm.order.orders.watchdog;

import dgm.order.orders.repository.OrderRepository;
import dgm.order.orders.service.SagaOrchestrator;
import dgm.order.orders.service.SagaOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Сторож заказов (компонент {@code watchdog}, ADR-004, SM-01/T6 и T12): раз в минуту находит заказы, чьи события потерялись, и передаёт их
 * оркестратору. Сторож ничего не решает сам и статус не меняет: переход просит у домена тот же код, что и событие, поэтому событие и сторож
 * вместе не навредят (побеждает то, что зафиксировалось первым, второе находит другой статус и ничего не делает).
 * <ul>
 *   <li>«создан» старше 60 секунд: упал процесс или потерян ответ соседа между шагами 1 и 3. Отмена с причиной «системная ошибка» (T12) и
 *       событие {@code order.cancelled}, по которому остатки снимают резерв, если он успел появиться;</li>
 *   <li>«ожидает оплаты» с резервом, истёкшим больше 5 минут назад: потеряно {@code reservation.expired}. Отмена по сроку (T6).</li>
 * </ul>
 * Зачем сторож, если есть Outbox: Outbox доставляет то, что сервис записал, а сторож находит то, что не было записано (падение между шагами
 * саги) или не дошло от соседа. Проход безопасен при двух экземплярах сервиса: переход под блокировкой строки выполнит только один.
 */
public final class OrderWatchdog {

    private static final Logger LOG = LoggerFactory.getLogger(OrderWatchdog.class);

    private final OrderRepository orders;
    private final SagaOrchestrator saga;
    private final WatchdogSettings settings;
    private final Clock clock;
    private final MeterRegistry meters;

    public OrderWatchdog(OrderRepository orders, SagaOrchestrator saga, WatchdogSettings settings, Clock clock, MeterRegistry meters) {
        this.orders = orders;
        this.saga = saga;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${dgm.order.watchdog.interval:PT1M}", initialDelayString = "${dgm.order.watchdog.initial-delay:PT30S}")
    void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.error("Проход сторожа заказов завершился ошибкой, повторится в следующий раз", e);
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько заказов исправлено
     */
    public int runOnce() {
        Instant now = clock.instant();
        int repaired = 0;
        List<UUID> abandoned = orders.findCreatedBefore(now.minus(settings.createdTimeout()), settings.batch());
        for (UUID orderId : abandoned) {
            repaired += repair(orderId, "abandoned-created", () -> saga.abandonedCreated(orderId));
        }
        List<UUID> expired = orders.findAwaitingReserveEndedBefore(now.minus(settings.reserveGrace()), settings.batch());
        for (UUID orderId : expired) {
            repaired += repair(orderId, "lost-reservation-expired", () -> saga.reservationExpired(orderId));
        }
        return repaired;
    }

    /** Один заказ: сбой на нём не мешает остальным, его подберёт следующий проход. */
    private int repair(UUID orderId, String kind, Supplier<SagaOutcome> step) {
        try {
            if (step.get().kind() != SagaOutcome.Kind.APPLIED) {
                return 0;
            }
        } catch (RuntimeException e) {
            LOG.error("Сторож не смог исправить заказ {} ({}), повторит в следующий проход", orderId, kind, e);
            return 0;
        }
        LOG.warn("Сторож исправил заказ {}: {}", orderId, kind);
        meters.counter("orders_watchdog_repairs_total", "kind", kind).increment();
        return 1;
    }
}
