package dgm.platform.notification.service;

import dgm.platform.notification.domain.WebhookDelivery;
import dgm.platform.notification.domain.WebhookDeliveryFilter;
import dgm.platform.notification.domain.WebhookDeliveryPage;
import dgm.platform.notification.repository.WebhookDeliveryRepository;
import java.util.List;
import java.util.Optional;

/**
 * Список доставок webhook продавца ({@code listWebhookDeliveries}, компонент {@code webhook-delivery-controller}, OP-102, US-11.4). Продавца задаёт
 * вызывающий из токена, сервис другого продавца не подставляет (U3). Ручного повтора нет (Р-193).
 */
public final class WebhookDeliveryService {

    private final WebhookDeliveryRepository deliveries;

    public WebhookDeliveryService(WebhookDeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    public WebhookDeliveryPage list(WebhookDeliveryFilter filter) {
        List<WebhookDelivery> rows = deliveries.findBySeller(filter);
        boolean more = rows.size() > filter.limit();
        List<WebhookDelivery> items = more ? rows.subList(0, filter.limit()) : rows;
        Optional<WebhookDeliveryFilter.After> next = more
                ? Optional.of(new WebhookDeliveryFilter.After(items.get(items.size() - 1).createdAt(), items.get(items.size() - 1).id()))
                : Optional.empty();
        return new WebhookDeliveryPage(List.copyOf(items), filter.limit(), next);
    }
}
