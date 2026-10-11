package dgm.platform.notification.handler;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.platform.notification.api.InvalidNotificationException;
import dgm.platform.notification.api.NotificationRequest;
import dgm.platform.notification.api.Notifications;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Письмо владельцу о каждом входе по коду из SMS (FT-1.7, компонент {@code notification-handler}): событие {@code user.sms-login} несёт
 * только идентификатор пользователя, время входа берётся из атрибута {@code time} конверта. Номера и кода в событии и в письме нет.
 */
@Component
public final class SmsLoginHandler implements EventHandler {

    static final String TYPE = "user.sms-login";

    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler), у обеих тем оно одно. */
    static final String NAME = "notification-handler";

    private final Notifications notifications;

    public SmsLoginHandler(Notifications notifications) {
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
        Map<String, Object> params = Map.of("loggedInAt", DateTimeFormatter.ISO_INSTANT.format(event.time()));
        try {
            boolean created = notifications.enqueue(new NotificationRequest(userId, "user-sms-login", params, event.id()));
            return created ? HandleResult.done() : HandleResult.ignored("duplicate-notification");
        } catch (InvalidNotificationException e) {
            throw new FatalEventException(e.getMessage());
        }
    }
}
