package dgm.platform.notification.handler;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.platform.notification.api.InvalidNotificationException;
import dgm.platform.notification.api.NotificationRequest;
import dgm.platform.notification.api.Notifications;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Письмо владельцу о деактивации учётной записи (FT-11.0, компонент {@code notification-handler}): событие {@code user.deactivated} ставит
 * в очередь письмо {@code user-deactivated} без параметров. Адрес берётся у identity при отправке и принадлежит уже деактивированной записи
 * ({@code UserContacts.findDeactivated}): письма активным адресатам такой записи не пишут, а это единственное, которое ей положено. Если к
 * моменту отправки запись обезличена, адреса нет, и письмо получает исход {@code dropped} без события. Имя администратора, выполнившего
 * деактивацию, в письмо не попадает. Повторная доставка отсекается отметкой {@code processed_event} обёртки потребителя, а при её обходе
 * ключом {@code (source_event_id, template, user_id)}.
 */
@Component
public final class UserDeactivatedHandler implements EventHandler {

    static final String TYPE = "user.deactivated";
    static final String TEMPLATE = "user-deactivated";

    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler). */
    static final String NAME = "notification-handler";

    private final Notifications notifications;

    public UserDeactivatedHandler(Notifications notifications) {
        this.notifications = notifications;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String module() {
        return "notification";
    }

    @Override
    public String topic() {
        return "identity.events";
    }

    @Override
    public Set<String> types() {
        return Set.of(TYPE);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        UUID userId = EventData.uuid(event.data(), "userId");
        try {
            boolean created = notifications.enqueue(new NotificationRequest(userId, TEMPLATE, Map.of(), event.id()));
            return created ? HandleResult.done() : HandleResult.ignored("duplicate-notification");
        } catch (InvalidNotificationException e) {
            throw new FatalEventException(e.getMessage());
        }
    }
}
