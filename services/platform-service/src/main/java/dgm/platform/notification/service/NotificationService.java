package dgm.platform.notification.service;

import dgm.kit.id.Uuids;
import dgm.platform.notification.api.InvalidNotificationException;
import dgm.platform.notification.api.NotificationRequest;
import dgm.platform.notification.api.Notifications;
import dgm.platform.notification.domain.NewNotification;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.repository.NotificationRepository;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Постановка уведомлений в очередь (компонент {@code notification-service}). Параметры проверяются по шаблону до записи: письмо, которое
 * заведомо не соберётся, в очередь не попадает, и ошибка видна обработчику события сразу, а не при отправке. Адреса получателя здесь нет,
 * он берётся у identity в момент отправки ({@link NotificationDispatcher}).
 */
public final class NotificationService implements Notifications {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);
    /** Ключи, которых в параметрах быть не может: столбец {@code params} их отвергает (ck_notification_params, INV-44). */
    private static final Set<String> PERSONAL_KEYS = Set.of("email", "phone", "name", "address", "recipient");
    private static final Pattern TEMPLATE = Pattern.compile("^[a-z0-9_.-]{1,64}$");

    private final NotificationRepository repository;
    private final Letters letters;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, Letters letters, Clock clock) {
        this.repository = repository;
        this.letters = letters;
        this.clock = clock;
    }

    @Override
    public boolean enqueue(NotificationRequest request) {
        if (!TEMPLATE.matcher(request.template()).matches()) {
            throw new InvalidNotificationException("Код шаблона не подходит формату");
        }
        if (letters.directOnly().contains(request.template())) {
            throw new InvalidNotificationException("Шаблон " + request.template() + " уходит только напрямую: код в очередь и в базу не попадает");
        }
        for (Map.Entry<String, Object> param : request.params().entrySet()) {
            if (PERSONAL_KEYS.contains(param.getKey())) {
                throw new InvalidNotificationException("Параметр " + param.getKey() + " зарезервирован: персональных данных в очереди нет (INV-44)");
            }
        }
        letters.validate(request.template(), request.params());
        boolean created = repository.insert(new NewNotification(Uuids.v7(clock), request.userId(), request.template(), request.params(),
                request.sourceEventId(), clock.instant()));
        if (created) {
            LOG.info("Уведомление {} для пользователя {} поставлено в очередь", request.template(), request.userId());
        } else {
            LOG.info("Уведомление {} по событию {} уже есть, второе не создано", request.template(), request.sourceEventId());
        }
        return created;
    }
}
