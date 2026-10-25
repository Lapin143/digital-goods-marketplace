package dgm.delivery.delivery.service;

import dgm.delivery.delivery.domain.DeliveryState;
import dgm.delivery.delivery.domain.DeliveryStatus;
import dgm.delivery.delivery.domain.FailReason;
import dgm.delivery.delivery.domain.ProviderStatus;
import dgm.delivery.delivery.domain.StatusNotice;
import dgm.delivery.delivery.repository.DeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Применение статуса письма от провайдера (SM-06/T5 и T6, ADR-011): общий код для вебхука и для опроса, поэтому потерянный вебхук закрывается
 * опросом без второй реализации. Каждый статус применяется один раз: пара «выдача, статус» записывается в {@code provider_status_event}
 * в одной транзакции с переходом, повтор получает {@link Result#DUPLICATE} и ничего не меняет.
 *
 * <ul>
 *   <li>{@code delivered}: «отправлено» в «доставлено», время доставки по данным провайдера, событие {@code delivery.delivered}, контроль 30 минут
 *       закрывается (открытый и уже переданный в поддержку: запоздавшая доставка закрывает обращение тем же событием);</li>
 *   <li>{@code failed}: «отправлено» в «ошибка» (причина {@code address_rejected}, Р-152), событие {@code delivery.failed};</li>
 *   <li>{@code accepted}: только запись, провайдер принял письмо, а платформа это уже знает;</li>
 *   <li>статус, который в состояние выдачи не вписывается («ошибка» после «доставлено», «доставлено» после «ошибки»), ничего не меняет и
 *       не записывается ({@link Result#CONFLICT}): подтверждённая доставка не отзывается;</li>
 *   <li>выдача ещё «в очереди» (статус обогнал запись исхода попытки): {@link Result#PREMATURE}, ничего не записывается, статус придёт опросом.</li>
 * </ul>
 * В журнал и метрики идут идентификаторы и коды; адреса и текста письма здесь нет.
 */
public final class DeliveryStatuses {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryStatuses.class);

    /** Исход применения для ответа и метрики. */
    public enum Result {
        APPLIED("applied"),
        RECORDED("recorded"),
        DUPLICATE("duplicate"),
        UNKNOWN("unknown"),
        PREMATURE("premature"),
        CONFLICT("conflict"),
        IGNORED("ignored");

        private final String label;

        Result(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Статус не вписывается в состояние выдачи: транзакция откатывается вместе с записью статуса. */
    private static final class ConflictException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ConflictException() {
            super(null, null, false, false);
        }
    }

    private final DeliveryRepository deliveries;
    private final DeliveryTransaction transaction;
    private final DeliverySettings settings;
    private final Clock clock;
    private final MeterRegistry meters;

    public DeliveryStatuses(DeliveryRepository deliveries, DeliveryTransaction transaction, DeliverySettings settings, Clock clock, MeterRegistry meters) {
        this.deliveries = deliveries;
        this.transaction = transaction;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    /** Применяет статус. Не бросает исключений на неизвестное письмо, повтор и конфликт: провайдеру на всё это отвечают 200. */
    public Result apply(StatusNotice notice) {
        Result result = process(notice);
        meters.counter("delivery_provider_statuses_total", "status", notice.status().wire(), "result", result.label()).increment();
        return result;
    }

    private Result process(StatusNotice notice) {
        UUID id = notice.deliveryId().orElse(null);
        if (id == null) {
            return Result.UNKNOWN;
        }
        if (notice.status() == ProviderStatus.OTHER) {
            return Result.IGNORED;
        }
        try {
            return transaction.execute(() -> applyInTransaction(id, notice));
        } catch (ConflictException e) {
            LOG.warn("Статус {} письма по выдаче {} не вписывается в её состояние, не применён", notice.status().wire(), id);
            return Result.CONFLICT;
        }
    }

    private Result applyInTransaction(UUID id, StatusNotice notice) {
        DeliveryState state = deliveries.find(id).orElse(null);
        if (state == null) {
            return Result.UNKNOWN;
        }
        if (DeliveryStatus.QUEUED.equals(state.status())) {
            return Result.PREMATURE;
        }
        Instant now = clock.instant();
        if (!deliveries.recordProviderStatus(id, notice.status().wire(), notice.occurredAt(), now)) {
            return Result.DUPLICATE;
        }
        return switch (notice.status()) {
            case ACCEPTED -> Result.RECORDED;
            case DELIVERED -> delivered(state, notice, now);
            case FAILED -> undelivered(state, notice);
            case OTHER -> Result.IGNORED;
        };
    }

    private Result delivered(DeliveryState state, StatusNotice notice, Instant now) {
        if (!deliveries.markDelivered(state.id(), notice.occurredAt())) {
            throw new ConflictException();
        }
        deliveries.closeWatch(state.orderId(), now);
        transaction.publish(DeliveryEvents.TOPIC, DeliveryEvents.AGGREGATE, state.orderId().toString(), DeliveryEvents.DELIVERED, DeliveryEvents.SCHEMA_VERSION,
                DeliveryEvents.delivered(state.id(), state.orderId(), state.kind(), notice.occurredAt()));
        if (state.primary()) {
            // NFT-2.0: время от оплаты считается только для первичной выдачи, повторная его не искажает
            deliveries.watchDeadline(state.orderId()).ifPresent(deadline -> {
                Duration sincePayment = Duration.between(deadline.minus(settings.controlWindow()), notice.occurredAt());
                meters.timer("delivery_time_seconds", "stage", "delivered").record(sincePayment.isNegative() ? Duration.ZERO : sincePayment);
            });
        }
        return Result.APPLIED;
    }

    private Result undelivered(DeliveryState state, StatusNotice notice) {
        String code = "provider_" + ("address_rejected".equals(notice.reason()) ? "address_rejected" : "other");
        if (!deliveries.markUndelivered(state.id(), FailReason.ADDRESS_REJECTED, code)) {
            throw new ConflictException();
        }
        transaction.publish(DeliveryEvents.TOPIC, DeliveryEvents.AGGREGATE, state.orderId().toString(), DeliveryEvents.FAILED, DeliveryEvents.SCHEMA_VERSION,
                DeliveryEvents.failed(state.id(), state.orderId(), state.kind(), FailReason.ADDRESS_REJECTED, state.attemptNo()));
        meters.counter("delivery_failed_total", "reason", FailReason.ADDRESS_REJECTED).increment();
        return Result.APPLIED;
    }
}
