package dgm.platform.notification.handler;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.platform.notification.api.InvalidNotificationException;
import dgm.platform.notification.api.NotificationRequest;
import dgm.platform.notification.api.Notifications;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Письма заявителю о решении модератора (FT-2.2, FT-11.4, компонент {@code notification-handler}): {@code seller.approved},
 * {@code seller.rejected} с причиной и {@code seller.returned} с комментарием. Получатель это {@code userId} события, шаблон назван по типу
 * события с дефисом вместо точки. Событие ставит в очередь одно письмо: повторная доставка отсекается отметкой {@code processed_event}
 * обёртки потребителя, а при её обходе ключом {@code (source_event_id, template)} в таблице. Текст причины и комментария попадает в
 * параметры шаблона, в журналы и сообщения об ошибках не пишется.
 */
@Component
public final class SellerDecisionHandler implements EventHandler {

    static final String APPROVED = "seller.approved";
    static final String REJECTED = "seller.rejected";
    static final String RETURNED = "seller.returned";
    /** Предел текста в схемах событий (AsyncAPI: maxLength 1000). */
    private static final int MAX_TEXT = 1000;

    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler), у обеих тем оно одно. */
    static final String NAME = "notification-handler";

    private final Notifications notifications;

    public SellerDecisionHandler(Notifications notifications) {
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
        return "catalog.events";
    }

    @Override
    public Set<String> types() {
        return Set.of(APPROVED, REJECTED, RETURNED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        Map<String, Object> data = event.data();
        UUID userId = EventData.uuid(data, "userId");
        Map<String, Object> params = new LinkedHashMap<>();
        switch (event.type()) {
            case APPROVED -> params.put("sellerType", EventData.text(data, "sellerType", 32));
            case REJECTED -> {
                params.put("reason", EventData.text(data, "reason", MAX_TEXT));
                params.put("nextApplicationAt", EventData.text(data, "nextApplicationAt", 40));
            }
            case RETURNED -> params.put("comment", EventData.text(data, "comment", MAX_TEXT));
            default -> throw new FatalEventException("Обработчик не принимает события типа " + event.type());
        }
        try {
            boolean created = notifications.enqueue(new NotificationRequest(userId, event.type().replace('.', '-'), params, event.id()));
            return created ? HandleResult.done() : HandleResult.ignored("duplicate-notification");
        } catch (InvalidNotificationException e) {
            throw new FatalEventException(e.getMessage());
        }
    }
}
