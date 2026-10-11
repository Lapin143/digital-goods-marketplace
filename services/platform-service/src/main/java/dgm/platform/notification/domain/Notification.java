package dgm.platform.notification.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Уведомление из очереди (таблица {@code notification.notification}) в объёме, нужном отправителю. Адреса получателя здесь нет (INV-44),
 * параметры шаблона содержат только идентификаторы, статусы и текст решения модератора.
 *
 * @param id            идентификатор, он же идентификатор сообщения у провайдера почты
 * @param userId        получатель
 * @param template      код шаблона
 * @param channel       канал, в срезе 1 всегда {@code email}
 * @param params        параметры шаблона
 * @param sourceEventId событие-источник
 * @param attempts      сколько попыток отправки уже сделано
 * @param createdAt     когда поставлено в очередь
 */
public record Notification(UUID id, UUID userId, String template, String channel, Map<String, Object> params, Optional<UUID> sourceEventId,
                           int attempts, Instant createdAt) {

    public static final String EMAIL = "email";

    /** Идентификаторы без параметров: в них может быть текст решения модератора. */
    @Override
    public String toString() {
        return "Notification[" + id + ", " + template + ", user=" + userId + ", attempts=" + attempts + "]";
    }
}
