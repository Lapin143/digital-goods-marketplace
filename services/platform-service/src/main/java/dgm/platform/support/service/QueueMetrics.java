package dgm.platform.support.service;

import dgm.platform.support.domain.QueueDepth;
import dgm.platform.support.repository.SupportRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Метрики очереди поддержки (Р-163, US-7.2 сценарий 5, NFT-6.1, NFT-2.4). Значения не копятся по событиям, а считаются запросом по индексу
 * {@code ix_ticket_status_created_at}: пропущенное событие, откат или правка руками не сдвигают счётчик навсегда, следующий проход
 * возвращает значение, равное числу открытых обращений.
 *
 * <ul>
 *   <li>{@code support_queue_tickets{status}}: обращений в статусах {@code created} и {@code in_progress};</li>
 *   <li>{@code support_queue_oldest_age_seconds}: сколько секунд ждёт самое старое обращение в статусе {@code created}; ноль, если очередь пуста.</li>
 * </ul>
 *
 * <p>Оповещение {@code SupportTicketUnattended} срабатывает, когда второе значение выше часа (SM-07, NFT-2.4).
 */
public final class QueueMetrics {

    public static final String TICKETS = "support_queue_tickets";
    public static final String OLDEST_AGE = "support_queue_oldest_age_seconds";

    private final SupportRepository repository;
    private final Clock clock;
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong inProgress = new AtomicLong();
    private final AtomicLong oldestAge = new AtomicLong();

    public QueueMetrics(SupportRepository repository, Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.clock = clock;
        metrics.gauge(TICKETS, Tags.of("status", "created"), created);
        metrics.gauge(TICKETS, Tags.of("status", "in_progress"), inProgress);
        metrics.gauge(OLDEST_AGE, oldestAge);
    }

    /** Один проход: читает состояние очереди и обновляет значения метрик. */
    public QueueDepth refresh() {
        QueueDepth depth = repository.queueDepth();
        created.set(depth.created());
        inProgress.set(depth.inProgress());
        oldestAge.set(depth.oldestCreated().map(at -> Math.max(0, Duration.between(at, clock.instant()).toSeconds())).orElse(0L));
        return depth;
    }
}
