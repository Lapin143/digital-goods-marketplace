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
 * Письма покупателю о заказе (FT-11.0, US-5.2, US-5.3, US-5.7, Р-138, компонент {@code notification-handler}): {@code order.created}
 * («Заказ создан» со ссылкой на страницу оплаты и сроком), {@code order.paid} («Заказ оплачен», без ключа) и {@code order.refunded}
 * («Деньги возвращены»). Получатель это {@code buyerId} события, шаблон назван по типу события с дефисом вместо точки. Адрес письму даёт
 * identity в момент отправки, а не снимок адреса из {@code order.paid}: в очереди адресов нет (INV-44), и письмо о возврате, у которого
 * снимка нет вовсе, идёт тем же путём (Р-153). Ключей в событиях и письмах нет: значения ключей уходят только письмом выдачи.
 */
@Component
public final class OrderLetterHandler implements EventHandler {

    static final String CREATED = "order.created";
    static final String PAID = "order.paid";
    static final String REFUNDED = "order.refunded";
    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler), у всех тем оно одно. */
    static final String NAME = "notification-handler";

    private final Notifications notifications;

    public OrderLetterHandler(Notifications notifications) {
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
        return "order.events";
    }

    @Override
    public Set<String> types() {
        return Set.of(CREATED, PAID, REFUNDED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        Map<String, Object> data = event.data();
        UUID buyerId = EventData.uuid(data, "buyerId");
        Map<String, Object> params = new LinkedHashMap<>();
        switch (event.type()) {
            case CREATED -> {
                params.put("orderNumber", EventData.number(data, "orderNumber"));
                params.put("total", EventData.money(data, "total"));
                params.put("orderId", EventData.uuid(data, "orderId").toString());
                params.put("payUntil", EventData.text(data, "paymentSessionExpiresAt", 40));
            }
            case PAID -> {
                params.put("orderNumber", EventData.number(data, "orderNumber"));
                params.put("productTitle", EventData.text(data, "productTitle", 1000));
                params.put("total", EventData.money(data, "total"));
            }
            case REFUNDED -> {
                params.put("orderNumber", EventData.number(data, "orderNumber"));
                params.put("total", EventData.money(data, "total"));
                params.put("refundReason", EventData.text(data, "refundReason", 64));
            }
            default -> throw new FatalEventException("Обработчик не принимает события типа " + event.type());
        }
        try {
            boolean created = notifications.enqueue(new NotificationRequest(buyerId, event.type().replace('.', '-'), params, event.id()));
            return created ? HandleResult.done() : HandleResult.ignored("duplicate-notification");
        } catch (InvalidNotificationException e) {
            throw new FatalEventException(e.getMessage());
        }
    }
}
