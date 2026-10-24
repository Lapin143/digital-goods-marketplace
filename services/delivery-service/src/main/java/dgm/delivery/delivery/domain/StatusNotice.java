package dgm.delivery.delivery.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Статус письма от провайдера: пришёл вебхуком или получен опросом. Идентификатор сообщения у провайдера равен идентификатору выдачи (ADR-006),
 * поэтому по нему выдача находится без отдельной таблицы соответствия. Причина есть у отказа ({@code address_rejected} или {@code other}).
 *
 * @param messageId  идентификатор сообщения у провайдера, непрозрачная строка до 128 знаков
 * @param status     статус
 * @param occurredAt время события по данным провайдера
 * @param reason     причина отказа или {@code null}
 */
public record StatusNotice(String messageId, ProviderStatus status, Instant occurredAt, String reason) {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public StatusNotice {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** Выдача, о письме которой речь; пусто, если идентификатор провайдера не похож на идентификатор выдачи (письмо не наше). */
    public Optional<UUID> deliveryId() {
        return ID.matcher(messageId).matches() ? Optional.of(UUID.fromString(messageId)) : Optional.empty();
    }
}
