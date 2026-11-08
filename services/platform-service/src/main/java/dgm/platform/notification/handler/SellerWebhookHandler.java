package dgm.platform.notification.handler;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.id.Uuids;
import dgm.kit.time.Timestamps;
import dgm.platform.notification.domain.NewWebhookDelivery;
import dgm.platform.notification.repository.WebhookDeliveryRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Уведомления продавцу о заказах (FT-10.3, US-11.4, Р-193, компонент {@code seller-webhook-handler}): по {@code order.paid}, {@code order.issued},
 * {@code order.cancelled} и {@code order.refunded} ставит в очередь уведомление для продавца заказа, в той же транзакции, что и запись
 * {@code processed_event}. Повторная обработка события новой строки не даёт (уникальный {@code event_id}, ADR-006).
 *
 * <p>Тело собирается здесь из события и дальше не меняется: {@code eventId}, {@code type}, {@code occurredAt}, {@code orderNumber}, {@code productId},
 * {@code quantity}, {@code amount} (сумма заказа с валютой). Покупателя, его адреса и телефона в теле нет (INV-44), как и ключей: значения ключей
 * уходят только письмом выдачи. Есть ли у продавца действующее подключение, обработчик не спрашивает: вызов каталога внутри транзакции запрещён
 * (правило 9), поэтому подключение проверяет отправитель в момент отправки и закрывает уведомление статусом {@code skipped}.
 *
 * <p>Три события получили необязательные поля ради этого обработчика (R2): {@code order.cancelled} ({@code sellerId}, {@code orderNumber}, {@code total}),
 * {@code order.issued} ({@code quantity}), {@code order.refunded} ({@code productId}, {@code quantity}). Событие, изданное до обновления, не несёт ни одного
 * из них: его продавцу никто не обещал (webhook тогда не существовал), оно пропускается с причиной {@code legacy-event} и в очередь недоставленных не
 * попадает. Частичный набор полей это ошибка издателя: событие уходит в очередь недоставленных сразу.
 */
@Component
public final class SellerWebhookHandler implements EventHandler {

    static final String PAID = "order.paid";
    static final String ISSUED = "order.issued";
    static final String CANCELLED = "order.cancelled";
    static final String REFUNDED = "order.refunded";
    /** Имя потребителя в {@code processed_event}: отдельное от писем покупателю, иначе событие, уже принятое письмом, обработчик webhook пропустил бы. */
    static final String NAME = "seller-webhook-handler";
    /** Поля, добавленные в события R2 ради webhook: у события старого вида нет ни одного из них. */
    private static final Map<String, List<String>> ADDED_IN_R2 = Map.of(CANCELLED, List.of("sellerId", "orderNumber", "total"), ISSUED, List.of("quantity"),
            REFUNDED, List.of("productId", "quantity"));

    private static final Logger LOG = LoggerFactory.getLogger(SellerWebhookHandler.class);

    private final WebhookDeliveryRepository deliveries;
    private final Clock clock;

    public SellerWebhookHandler(WebhookDeliveryRepository deliveries, Clock clock) {
        this.deliveries = deliveries;
        this.clock = clock;
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
        return Set.of(PAID, ISSUED, CANCELLED, REFUNDED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        if (!types().contains(event.type())) {
            throw new FatalEventException("Обработчик не принимает события типа " + event.type());
        }
        Map<String, Object> data = event.data();
        List<String> added = ADDED_IN_R2.getOrDefault(event.type(), List.of());
        if (!added.isEmpty() && added.stream().allMatch(field -> data.get(field) == null)) {
            LOG.info("Событие {} {} издано до R2 и не несёт полей для webhook: уведомление продавцу не создаётся", event.type(), event.id());
            return HandleResult.ignored("legacy-event");
        }
        UUID sellerId = EventData.uuid(data, "sellerId");
        long orderNumber = EventData.integer(data, "orderNumber", 1001, Long.MAX_VALUE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.id().toString());
        payload.put("type", event.type());
        payload.put("occurredAt", Timestamps.format(event.time()));
        payload.put("orderNumber", orderNumber);
        payload.put("productId", EventData.uuid(data, "productId").toString());
        payload.put("quantity", EventData.integer(data, "quantity", 1, 10));
        payload.put("amount", EventData.moneyObject(data, "total"));
        boolean created = deliveries.insert(new NewWebhookDelivery(Uuids.v7(clock), sellerId, event.id(), event.type(), orderNumber, payload, clock.instant()));
        return created ? HandleResult.done() : HandleResult.ignored("duplicate-webhook");
    }
}
