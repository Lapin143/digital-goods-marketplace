package dgm.delivery.delivery.service;

import dgm.delivery.delivery.domain.AttemptResult;
import dgm.delivery.delivery.domain.DeliverySchedule;
import dgm.delivery.delivery.domain.FailReason;
import dgm.delivery.delivery.domain.QueuedDelivery;
import dgm.delivery.delivery.repository.DeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Запись исхода попытки (SM-06/T2, T3, T4, ADR-011): короткой транзакцией, уже после вызовов соседа и провайдера. Попытка, выдача и событие
 * в Outbox фиксируются вместе. Запись сравнивает число выполненных попыток: если аренда истекла и попытку записал другой экземпляр, поздний
 * исход ничего не меняет ({@code false}). В журнал идут идентификаторы и код ошибки, адреса и текста письма там нет.
 */
public final class DeliveryOutcomes {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryOutcomes.class);

    private final DeliveryRepository deliveries;
    private final DeliveryTransaction transaction;
    private final DeliverySchedule schedule;
    private final Clock clock;
    private final MeterRegistry meters;

    public DeliveryOutcomes(DeliveryRepository deliveries, DeliveryTransaction transaction, DeliverySchedule schedule, Clock clock, MeterRegistry meters) {
        this.deliveries = deliveries;
        this.transaction = transaction;
        this.schedule = schedule;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * @param delivery  выдача в том виде, в каком её взяла аренда
     * @param result    исход попытки
     * @param startedAt когда попытка началась
     * @return {@code true}: исход записан; {@code false}: выдача уже изменена другим исполнителем
     */
    public boolean recordAttempt(QueuedDelivery delivery, AttemptResult result, Instant startedAt) {
        Instant finishedAt = clock.instant();
        boolean applied = transaction.execute(() -> switch (result) {
            case AttemptResult.Accepted accepted -> accepted(delivery, startedAt, finishedAt);
            case AttemptResult.Retryable retryable -> retryable(delivery, retryable.code(), startedAt, finishedAt);
            case AttemptResult.Permanent permanent -> failed(delivery, "permanent_error", permanent.code(), FailReason.ADDRESS_REJECTED, startedAt, finishedAt);
        });
        String outcome = switch (result) {
            case AttemptResult.Accepted accepted -> "accepted";
            case AttemptResult.Retryable retryable -> "retryable_error";
            case AttemptResult.Permanent permanent -> "permanent_error";
        };
        if (applied) {
            meters.counter("delivery_attempts_total", "outcome", outcome).increment();
        } else {
            LOG.warn("Исход попытки {} выдачи {} не записан: выдача уже изменена другим исполнителем", delivery.nextAttemptNo(), delivery.id());
            meters.counter("delivery_outcomes_stale_total").increment();
        }
        return applied;
    }

    private boolean accepted(QueuedDelivery d, Instant startedAt, Instant finishedAt) {
        if (!deliveries.markSent(d.id(), d.attemptNo(), startedAt, finishedAt)) {
            return false;
        }
        transaction.publish(DeliveryEvents.TOPIC, DeliveryEvents.AGGREGATE, d.orderId().toString(), DeliveryEvents.ACCEPTED, DeliveryEvents.SCHEMA_VERSION,
                DeliveryEvents.accepted(d.id(), d.orderId(), d.kind(), d.nextAttemptNo(), finishedAt));
        return true;
    }

    private boolean retryable(QueuedDelivery d, String code, Instant startedAt, Instant finishedAt) {
        int attemptNo = d.nextAttemptNo();
        if (schedule.exhaustedAfter(attemptNo)) {
            return failed(d, "retryable_error", code, FailReason.ATTEMPTS_EXHAUSTED, startedAt, finishedAt);
        }
        return deliveries.markRetry(d.id(), d.attemptNo(), code, schedule.dueAfter(attemptNo, finishedAt), startedAt, finishedAt);
    }

    private boolean failed(QueuedDelivery d, String outcome, String code, String reason, Instant startedAt, Instant finishedAt) {
        if (!deliveries.markFailed(d.id(), d.attemptNo(), outcome, code, reason, startedAt, finishedAt)) {
            return false;
        }
        transaction.publish(DeliveryEvents.TOPIC, DeliveryEvents.AGGREGATE, d.orderId().toString(), DeliveryEvents.FAILED, DeliveryEvents.SCHEMA_VERSION,
                DeliveryEvents.failed(d.id(), d.orderId(), d.kind(), reason, d.nextAttemptNo()));
        meters.counter("delivery_failed_total", "reason", reason).increment();
        return true;
    }
}
