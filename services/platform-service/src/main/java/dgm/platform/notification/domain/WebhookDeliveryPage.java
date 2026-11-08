package dgm.platform.notification.domain;

import java.util.List;
import java.util.Optional;

/** Страница списка доставок: строки, размер страницы и позиция следующей страницы (пусто на последней). */
public record WebhookDeliveryPage(List<WebhookDelivery> items, int limit, Optional<WebhookDeliveryFilter.After> next) {
}
