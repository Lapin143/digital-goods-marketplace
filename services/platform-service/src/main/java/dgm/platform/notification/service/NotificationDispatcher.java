package dgm.platform.notification.service;

import dgm.platform.identity.api.Contact;
import dgm.platform.identity.api.UserContacts;
import dgm.platform.notification.api.InvalidNotificationException;
import dgm.platform.notification.domain.FailureReason;
import dgm.platform.notification.domain.Notification;
import dgm.platform.notification.domain.RetryPolicy;
import dgm.platform.notification.letter.Letter;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Отправитель уведомлений (компонент {@code notification-service}): берёт из очереди письма, срок которых настал, собирает текст из
 * шаблона, узнаёт адрес у identity по идентификатору пользователя и передаёт письмо провайдеру. Адрес живёт в памяти только на время
 * вызова и нигде не сохраняется (INV-44).
 *
 * <p>Порядок для одного письма: уведомление берётся в аренду одним оператором ({@code FOR UPDATE SKIP LOCKED}, срок следующей попытки
 * сдвигается на срок аренды), затем без открытой транзакции идёт вызов провайдера, затем короткая запись результата. Соединение с базой
 * на время сетевого вызова не удерживается, два экземпляра сервиса одно письмо не отправляют, а если экземпляр упал после отправки, но до
 * записи результата, письмо уйдёт ещё раз с тем же {@code messageId} и провайдер его не продублирует.
 *
 * <p>Исходы: провайдер принял письмо, статус {@code sent}; временный сбой, следующая попытка через 1, 5, 25 секунд; после четвёртой неудачи
 * или при отказе провайдера статус {@code failed}, признак очереди недоставленных и событие {@code notification.failed} в одной транзакции
 * с записью статуса; получателя нет (запись деактивирована или анонимизирована) или шаблон не собирается: статус {@code failed} без события,
 * повтор ничего не изменит и сообщать об этом некому.
 */
public final class NotificationDispatcher {

    /** Тема событий уведомлений и тип агрегата (AsyncAPI, канал notificationEvents). */
    public static final String EVENTS_TOPIC = "notification.events";
    public static final String AGGREGATE = "Notification";
    public static final String FAILED = "notification.failed";

    static final String RECIPIENT_UNAVAILABLE = "recipient_unavailable";
    static final String TEMPLATE_ERROR = "template_error";

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRepository repository;
    private final UserContacts contacts;
    private final Letters letters;
    private final EmailSender sender;
    private final NotificationTransaction transaction;
    private final RetryPolicy retries;
    private final Duration lease;
    private final int batch;
    private final Clock clock;
    private final MeterRegistry metrics;

    public NotificationDispatcher(NotificationRepository repository, UserContacts contacts, Letters letters, EmailSender sender,
                                  NotificationTransaction transaction, RetryPolicy retries, Duration lease, int batch, Clock clock,
                                  MeterRegistry metrics) {
        this.repository = repository;
        this.contacts = contacts;
        this.letters = letters;
        this.sender = sender;
        this.transaction = transaction;
        this.retries = retries;
        this.lease = lease;
        this.batch = batch;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Просмотр очереди по расписанию. Ошибка одного просмотра не останавливает расписание. */
    @Scheduled(fixedDelayString = "${dgm.notification.poll-interval:PT0.5S}", initialDelayString = "${dgm.notification.initial-delay:PT2S}")
    public void poll() {
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            LOG.error("Просмотр очереди уведомлений не удался: {}", e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Отправляет письма, срок которых настал, но не больше {@code batch} за раз.
     *
     * @return сколько уведомлений обработано
     */
    public int dispatchDue() {
        int processed = 0;
        while (processed < batch) {
            Instant now = clock.instant();
            Optional<Notification> claimed = repository.claim(now, now.plus(lease));
            if (claimed.isEmpty()) {
                break;
            }
            process(claimed.get());
            processed++;
        }
        return processed;
    }

    private void process(Notification notification) {
        Optional<Contact> recipient = contacts.findActive(notification.userId());
        if (recipient.isEmpty()) {
            fail(notification, notification.attempts(), RECIPIENT_UNAVAILABLE, null);
            return;
        }
        Letter letter;
        try {
            letter = letters.render(notification.template(), recipient.get().name(), notification.params());
        } catch (InvalidNotificationException e) {
            LOG.error("Уведомление {} не собирается из шаблона {}: {}", notification.id(), notification.template(), e.getMessage());
            fail(notification, notification.attempts(), TEMPLATE_ERROR, null);
            return;
        }
        int attempt = notification.attempts() + 1;
        try {
            sender.send(new OutgoingEmail(notification.id().toString(), recipient.get().email(), letter.subject(), letter.text()));
        } catch (EmailSendException e) {
            failed(notification, attempt, e);
            return;
        }
        if (repository.markSent(notification.id(), attempt, clock.instant())) {
            count(notification, "sent");
            LOG.info("Уведомление {} ({}) принято провайдером с попытки {}", notification.id(), notification.template(), attempt);
        } else {
            LOG.warn("Уведомление {} отправлено, но уже не в очереди", notification.id());
        }
    }

    private void failed(Notification notification, int attempt, EmailSendException error) {
        if (error.permanent()) {
            LOG.warn("Провайдер отверг уведомление {} ({}): {}", notification.id(), notification.template(), error.code());
            fail(notification, attempt, error.code(), FailureReason.ADDRESS_REJECTED);
            return;
        }
        Optional<Duration> delay = retries.delayAfter(attempt);
        if (delay.isEmpty()) {
            LOG.warn("Уведомление {} ({}) не доставлено после {} попыток: {}", notification.id(), notification.template(), attempt, error.code());
            fail(notification, attempt, error.code(), FailureReason.ATTEMPTS_EXHAUSTED);
            return;
        }
        Instant now = clock.instant();
        if (repository.reschedule(notification.id(), attempt, now.plus(delay.get()), error.code(), now)) {
            count(notification, "retry");
            LOG.warn("Уведомление {} ({}): попытка {} не удалась ({}), повтор через {} с", notification.id(), notification.template(), attempt,
                    error.code(), delay.get().toMillis() / 1000.0);
        }
    }

    /**
     * Окончательная неудача. Если причина недоставки задана, статус и событие {@code notification.failed} пишутся одной транзакцией и
     * уведомление попадает в очередь недоставленных; без причины (нет получателя, шаблон не собирается) событие не публикуется.
     */
    private void fail(Notification notification, int attempts, String errorCode, FailureReason reason) {
        Instant now = clock.instant();
        boolean changed = transaction.execute(() -> {
            if (!repository.markFailed(notification.id(), attempts, errorCode, reason != null, now)) {
                return false;
            }
            if (reason != null) {
                transaction.publish(EVENTS_TOPIC, AGGREGATE, notification.id().toString(), FAILED, 1, failedData(notification, attempts, reason));
            }
            return true;
        });
        if (changed) {
            count(notification, reason == null ? "dropped" : "failed");
        }
    }

    /** {@code NotificationFailedData}: идентификаторы без адреса и телефона. */
    static Map<String, Object> failedData(Notification notification, int attempts, FailureReason reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notificationId", notification.id().toString());
        data.put("channel", notification.channel());
        data.put("template", notification.template());
        data.put("recipientUserId", notification.userId().toString());
        data.put("attempts", attempts);
        data.put("reason", reason.code());
        return data;
    }

    private void count(Notification notification, String result) {
        metrics.counter("notifications_total", "template", notification.template(), "result", result).increment();
    }
}
