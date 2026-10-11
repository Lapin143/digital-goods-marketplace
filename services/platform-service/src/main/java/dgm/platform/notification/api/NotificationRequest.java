package dgm.platform.notification.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Запрос на уведомление.
 *
 * @param userId        получатель; адрес берётся у identity в момент отправки
 * @param template      код шаблона письма: {@code seller-approved}, {@code seller-rejected}, {@code seller-returned}, {@code user-sms-login}
 * @param params        параметры шаблона без адресов, телефонов и имён получателя (INV-44)
 * @param sourceEventId событие, из которого создано уведомление; вместе с шаблоном даёт ключ дедупликации, {@code null} для уведомлений не из события
 */
public record NotificationRequest(UUID userId, String template, Map<String, Object> params, UUID sourceEventId) {

    public NotificationRequest {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(template, "template");
        params = Map.copyOf(Objects.requireNonNull(params, "params"));
    }

    /** Без значений параметров: они могут содержать текст модератора, которого в журналах быть не должно. */
    @Override
    public String toString() {
        return "NotificationRequest[" + template + ", user=" + userId + ", event=" + sourceEventId + "]";
    }
}
