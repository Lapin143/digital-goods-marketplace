package dgm.platform.notification.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Уведомление, которое ставится в очередь. Первая попытка отправки назначается на {@code createdAt}.
 *
 * @param id            идентификатор (UUIDv7)
 * @param userId        получатель
 * @param template      код шаблона
 * @param params        параметры шаблона
 * @param sourceEventId событие-источник или {@code null}
 * @param createdAt     момент постановки
 */
public record NewNotification(UUID id, UUID userId, String template, Map<String, Object> params, UUID sourceEventId, Instant createdAt) {

    @Override
    public String toString() {
        return "NewNotification[" + id + ", " + template + ", user=" + userId + "]";
    }
}
