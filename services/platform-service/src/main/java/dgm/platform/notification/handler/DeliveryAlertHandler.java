package dgm.platform.notification.handler;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.platform.identity.api.AdminRecipients;
import dgm.platform.notification.api.Notifications;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Оповещение администратору о выдаче, которая не удалась или не уложилась в срок (NFT-2.4, FT-6.4, FT-7.3, Р-138, компонент
 * {@code notification-handler}): {@code delivery.failed} (попытки исчерпаны или адрес отклонён) и {@code delivery.overdue} (30 минут после
 * оплаты без доставки). Письмо уходит всем активным администраторам одновременно с созданием обращения системы в модуле поддержки;
 * заказ назван идентификатором, значений ключей нет ни в событии, ни в письме.
 */
@Component
public final class DeliveryAlertHandler implements EventHandler {

    static final String FAILED = "delivery.failed";
    static final String OVERDUE = "delivery.overdue";
    static final String NAME = "notification-handler";

    private final AdminAlerts alerts;

    public DeliveryAlertHandler(AdminRecipients admins, Notifications notifications) {
        this.alerts = new AdminAlerts(admins, notifications);
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
        return "delivery.events";
    }

    @Override
    public Set<String> types() {
        return Set.of(FAILED, OVERDUE);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        Map<String, Object> data = event.data();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", EventData.uuid(data, "orderId").toString());
        switch (event.type()) {
            case FAILED -> {
                params.put("reason", EventData.text(data, "reason", 64));
                params.put("attempts", EventData.number(data, "attempts"));
            }
            case OVERDUE -> {
                params.put("paidAt", EventData.text(data, "paidAt", 40));
                params.put("threshold", EventData.number(data, "thresholdSeconds"));
            }
            default -> throw new FatalEventException("Обработчик не принимает события типа " + event.type());
        }
        return alerts.send(event.id(), event.type().replace('.', '-'), params);
    }
}
