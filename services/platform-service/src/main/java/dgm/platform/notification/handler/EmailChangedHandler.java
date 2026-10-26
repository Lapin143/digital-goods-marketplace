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
 * Письма о смене e-mail (FT-11.0, UC-04, 6a.6, компонент {@code notification-handler}): событие {@code user.email-changed} ставит в очередь два письма,
 * на новый адрес ({@code email-changed-new}) и на прежний ({@code email-changed-previous}), чтобы владелец, не терявший почту, узнал о смене. Адресов в
 * событии нет: новый берётся у identity в момент отправки, прежний тоже, пока он хранится (7 суток). Время смены берётся из атрибута {@code time}
 * конверта. Повторная доставка отсекается отметкой {@code processed_event} обёртки потребителя, а при её обходе ключом
 * {@code (source_event_id, template, user_id)}: письмо по одному событию уходит один раз, а два разных шаблона друг друга не вытесняют.
 */
@Component
public final class EmailChangedHandler implements EventHandler {

    static final String TYPE = "user.email-changed";
    static final String TEMPLATE_NEW = "email-changed-new";
    static final String TEMPLATE_PREVIOUS = "email-changed-previous";

    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler). */
    static final String NAME = "notification-handler";

    private final Notifications notifications;

    public EmailChangedHandler(Notifications notifications) {
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
        Map<String, Object> params = Map.of("changedAt", DateTimeFormatter.ISO_INSTANT.format(event.time()));
        try {
            boolean created = notifications.enqueue(new NotificationRequest(userId, TEMPLATE_NEW, params, event.id()));
            created |= notifications.enqueue(new NotificationRequest(userId, TEMPLATE_PREVIOUS, params, event.id()));
            return created ? HandleResult.done() : HandleResult.ignored("duplicate-notification");
        } catch (InvalidNotificationException e) {
            throw new FatalEventException(e.getMessage());
        }
    }
}
