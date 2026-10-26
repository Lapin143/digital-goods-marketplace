package dgm.platform.notification.service;

import dgm.platform.notification.domain.FailureReason;
import dgm.platform.notification.domain.MailStatus;
import dgm.platform.notification.domain.MailStatusNotice;
import dgm.platform.notification.domain.Notification;
import dgm.platform.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Применение статуса письма платформы от почтового провайдера ({@code receiveMailStatus}, OP-84, Р-171). Статус записывается в
 * {@code provider_status_event} и меняет уведомление в одной транзакции модуля {@code notification}; повтор того же статуса получает
 * {@link Result#DUPLICATE} и ничего не меняет.
 *
 * <ul>
 *   <li>{@code delivered}: {@code sent} становится {@code delivered}. Если статус пришёл раньше, чем записан исход отправки (уведомление ещё
 *       {@code queued}), он только записывается, а перевод делает запись исхода ({@code markSent});</li>
 *   <li>{@code failed}: уведомление ({@code queued} или {@code sent}) становится {@code failed} с признаком очереди недоставленных и в той же
 *       транзакции публикуется {@code notification.failed}. «Один раз» обеспечивает условное обновление по статусу, а не проверка в коде: второй
 *       отказ, отказ после доставки и отказ после собственного исхода платформы строку не находят и события не дают;</li>
 *   <li>{@code accepted}: только запись, платформа это уже знает;</li>
 *   <li>идентификатор сообщения не наш или такого уведомления нет: ничего не происходит, ответ провайдеру тот же, что на любой другой статус,
 *       по нему нельзя узнать, существует ли уведомление.</li>
 * </ul>
 * Причина отказа {@code address_rejected} даёт в событии причину {@code address_rejected}, любая другая (и отсутствие причины) {@code attempts_exhausted}:
 * провайдер сам исчерпал попытки доставить письмо. В журнал и метрики идут идентификаторы и коды; адреса и текста письма здесь нет.
 */
public final class ReceiveMailStatusService {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiveMailStatusService.class);

    /** Исход применения для метрики. */
    public enum Result {
        /** Статус изменил уведомление. */
        APPLIED("applied"),
        /** Статус записан, уведомление не изменилось (принято, доставка раньше записи отправки, отказ после доставки). */
        RECORDED("recorded"),
        /** Такой статус этого письма уже был. */
        DUPLICATE("duplicate"),
        /** Письмо не наше или уведомления нет. */
        UNKNOWN("unknown"),
        /** Статус, которого платформа не знает. */
        IGNORED("ignored");

        private final String label;

        Result(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final NotificationRepository repository;
    private final NotificationTransaction transaction;
    private final Clock clock;
    private final MeterRegistry metrics;

    public ReceiveMailStatusService(NotificationRepository repository, NotificationTransaction transaction, Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.transaction = transaction;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Применяет статус. Не бросает исключений на неизвестное письмо, повтор и статус, который не вписывается: провайдеру на всё это отвечают 200. */
    public Result apply(MailStatusNotice notice) {
        Result result = process(notice);
        metrics.counter("notification_provider_statuses_total", "status", notice.status().wire(), "result", result.label()).increment();
        return result;
    }

    private Result process(MailStatusNotice notice) {
        UUID id = notice.notificationId().orElse(null);
        if (id == null) {
            return Result.UNKNOWN;
        }
        if (notice.status() == MailStatus.OTHER) {
            return Result.IGNORED;
        }
        return transaction.execute(() -> applyInTransaction(id, notice));
    }

    private Result applyInTransaction(UUID id, MailStatusNotice notice) {
        Notification notification = repository.find(id).orElse(null);
        if (notification == null) {
            return Result.UNKNOWN;
        }
        Instant now = clock.instant();
        if (!repository.recordProviderStatus(id, notice.status().wire(), notice.occurredAt(), now)) {
            return Result.DUPLICATE;
        }
        return switch (notice.status()) {
            case DELIVERED -> delivered(notification, now);
            case FAILED -> undelivered(notification, notice, now);
            case ACCEPTED, OTHER -> Result.RECORDED;
        };
    }

    private Result delivered(Notification notification, Instant now) {
        if (!repository.markDelivered(notification.id(), now)) {
            return Result.RECORDED;
        }
        count(notification, "delivered");
        return Result.APPLIED;
    }

    private Result undelivered(Notification notification, MailStatusNotice notice, Instant now) {
        FailureReason reason = "address_rejected".equals(notice.reason()) ? FailureReason.ADDRESS_REJECTED : FailureReason.ATTEMPTS_EXHAUSTED;
        String code = reason == FailureReason.ADDRESS_REJECTED ? "provider_address_rejected" : "provider_failed";
        Notification failed = repository.markFailedByProvider(notification.id(), code, now).orElse(null);
        if (failed == null) {
            LOG.info("Отказ провайдера по уведомлению {} не применён: оно уже доставлено или недоставлено", notification.id());
            return Result.RECORDED;
        }
        transaction.publish(NotificationDispatcher.EVENTS_TOPIC, NotificationDispatcher.AGGREGATE, failed.id().toString(), NotificationDispatcher.FAILED, 1,
                NotificationDispatcher.failedData(failed, failed.attempts(), reason));
        count(failed, "failed");
        LOG.warn("Провайдер сообщил о недоставке уведомления {} ({}): {}", failed.id(), failed.template(), code);
        return Result.APPLIED;
    }

    private void count(Notification notification, String result) {
        metrics.counter("notifications_total", "template", notification.template(), "result", result).increment();
    }
}
