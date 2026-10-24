package dgm.platform.notification.handler;

import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.platform.identity.api.AdminRecipients;
import dgm.platform.notification.api.InvalidNotificationException;
import dgm.platform.notification.api.NotificationRequest;
import dgm.platform.notification.api.Notifications;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Письмо одного события всем активным администраторам (NFT-2.4, FT-6.4, Р-138, Р-153). Каждому получателю своя строка очереди: ключ
 * дедупликации {@code (событие, шаблон, получатель)} позволяет одному событию дать несколько писем, а повторной обработке не даёт ни одного
 * лишнего. Если администраторов нет (так быть не должно, INV-38), событие не теряется молча: об этом остаётся предупреждение в журнале,
 * а обработчик отвечает «пропущено», потому что повтор события администраторов не создаст.
 */
final class AdminAlerts {

    private static final Logger LOG = LoggerFactory.getLogger(AdminAlerts.class);

    private final AdminRecipients admins;
    private final Notifications notifications;

    AdminAlerts(AdminRecipients admins, Notifications notifications) {
        this.admins = admins;
        this.notifications = notifications;
    }

    HandleResult send(UUID eventId, String template, Map<String, Object> params) {
        List<UUID> recipients = admins.activeAdminIds();
        if (recipients.isEmpty()) {
            LOG.warn("Оповещение {} по событию {} некому отправить: нет активных администраторов", template, eventId);
            return HandleResult.ignored("no-admins");
        }
        int created = 0;
        try {
            for (UUID recipient : recipients) {
                if (notifications.enqueue(new NotificationRequest(recipient, template, params, eventId))) {
                    created++;
                }
            }
        } catch (InvalidNotificationException e) {
            throw new FatalEventException(e.getMessage());
        }
        return created > 0 ? HandleResult.done() : HandleResult.ignored("duplicate-notification");
    }
}
