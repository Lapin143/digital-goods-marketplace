package dgm.platform.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Статус письма платформы от провайдера (вебхук {@code receiveMailStatus}). Идентификатор сообщения у провайдера равен идентификатору
 * уведомления ({@code HttpEmailClient}), поэтому уведомление находится без таблицы соответствия. Причина есть у отказа
 * ({@code address_rejected} или {@code other}).
 *
 * @param messageId  идентификатор сообщения у провайдера, непрозрачная строка до 128 знаков
 * @param status     статус
 * @param occurredAt время события по данным провайдера
 * @param reason     причина отказа или {@code null}
 */
public record MailStatusNotice(String messageId, MailStatus status, Instant occurredAt, String reason) {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public MailStatusNotice {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** Уведомление, о письме которого речь; пусто, если идентификатор провайдера не похож на идентификатор уведомления (письмо не наше). */
    public Optional<UUID> notificationId() {
        return ID.matcher(messageId).matches() ? Optional.of(UUID.fromString(messageId)) : Optional.empty();
    }
}
