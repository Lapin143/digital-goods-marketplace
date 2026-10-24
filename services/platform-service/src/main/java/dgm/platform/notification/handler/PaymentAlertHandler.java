package dgm.platform.notification.handler;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.HandleResult;
import dgm.platform.identity.api.AdminRecipients;
import dgm.platform.notification.api.Notifications;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Оповещение администратору об эскалации возврата (FT-6.4, Р-138, компонент {@code notification-handler}): {@code payment.refund-escalated}
 * значит, что автоматические повторы возврата исчерпаны и деньги нужно вернуть вручную. Письмо уходит всем активным администраторам, заказ
 * назван идентификатором: короткого номера в событии нет, а очередь ручных возвратов ищется по заказу.
 */
@Component
public final class PaymentAlertHandler implements EventHandler {

    static final String ESCALATED = "payment.refund-escalated";
    static final String NAME = "notification-handler";

    private final AdminAlerts alerts;

    public PaymentAlertHandler(AdminRecipients admins, Notifications notifications) {
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
        return "payment.events";
    }

    @Override
    public Set<String> types() {
        return Set.of(ESCALATED);
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
        params.put("amount", EventData.money(data, "amount"));
        params.put("attempts", EventData.number(data, "attempts"));
        return alerts.send(event.id(), event.type().replace('.', '-'), params);
    }
}
