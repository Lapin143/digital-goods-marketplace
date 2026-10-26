package dgm.platform.notification.handler;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.platform.identity.api.SellerRecipients;
import dgm.platform.notification.api.InvalidNotificationException;
import dgm.platform.notification.api.NotificationRequest;
import dgm.platform.notification.api.Notifications;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Письма продавцу о решении модератора по товару (FT-11.4, компонент {@code notification-handler}): {@code product.published},
 * {@code product.rejected} с причиной и {@code product.blocked} с причиной. Событие называет продавца идентификатором профиля
 * ({@code sellerId}), а письмо адресуется учётной записи, поэтому владельца профиля находит {@link SellerRecipients} (модуль {@code identity}):
 * идентификатор профиля и идентификатор пользователя различны. Если профиль ни к какой записи не привязан, письмо некому отправить: событие
 * принимается как «пропущено» с предупреждением в журнале, повтор записи не создаст. Блокировка вместе с продавцом ({@code cause = seller_blocked},
 * R2) письма по товару не вызывает: продавцу придёт одно письмо о блокировке учётной записи, а не по письму на каждый товар. Шаблон назван по типу
 * события с дефисом вместо точки. Название товара и причина идут параметрами письма, в журналы и сообщения об ошибках текст модератора не попадает.
 */
@Component
public final class ProductDecisionHandler implements EventHandler {

    static final String PUBLISHED = "product.published";
    static final String REJECTED = "product.rejected";
    static final String BLOCKED = "product.blocked";
    /** Блокировка по решению модератора: единственная причина, по которой продавцу пишут о товаре (вторая появится в R2). */
    static final String MODERATOR_DECISION = "moderator_decision";
    /** Предел названия и текста причины в схемах событий (AsyncAPI: title 200, reason 1000). */
    private static final int MAX_TITLE = 200;
    private static final int MAX_REASON = 1000;

    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler). */
    static final String NAME = "notification-handler";

    private static final Logger LOG = LoggerFactory.getLogger(ProductDecisionHandler.class);

    private final Notifications notifications;
    private final SellerRecipients sellers;

    public ProductDecisionHandler(Notifications notifications, SellerRecipients sellers) {
        this.notifications = notifications;
        this.sellers = sellers;
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
        return Set.of(PUBLISHED, REJECTED, BLOCKED);
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        Map<String, Object> data = event.data();
        UUID sellerId = EventData.uuid(data, "sellerId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("title", EventData.text(data, "title", MAX_TITLE));
        switch (event.type()) {
            case PUBLISHED -> {
                // только название: решение без причины, лишних строк в письме нет
            }
            case REJECTED -> params.put("reason", EventData.text(data, "reason", MAX_REASON));
            case BLOCKED -> {
                params.put("reason", EventData.text(data, "reason", MAX_REASON));
                String cause = EventData.text(data, "cause", 32);
                if (!MODERATOR_DECISION.equals(cause)) {
                    return HandleResult.ignored("seller-blocked");
                }
            }
            default -> throw new FatalEventException("Обработчик не принимает события типа " + event.type());
        }
        Optional<UUID> owner = sellers.ownerOf(sellerId);
        if (owner.isEmpty()) {
            LOG.warn("Письмо о решении по товару {} некому отправить: профиль продавца {} не привязан к учётной записи", event.subject(), sellerId);
            return HandleResult.ignored("seller-unknown");
        }
        try {
            boolean created = notifications.enqueue(new NotificationRequest(owner.get(), event.type().replace('.', '-'), params, event.id()));
            return created ? HandleResult.done() : HandleResult.ignored("duplicate-notification");
        } catch (InvalidNotificationException e) {
            throw new FatalEventException(e.getMessage());
        }
    }
}
