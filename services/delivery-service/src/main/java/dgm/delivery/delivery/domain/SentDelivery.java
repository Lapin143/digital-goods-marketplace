package dgm.delivery.delivery.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Выдача «отправлено», по которой провайдер ещё не прислал итоговый статус. Курсор опроса: порядок {@code (sent_at, id)} совпадает с индексом
 * {@code ix_delivery_sent_poll}.
 *
 * @param id     идентификатор выдачи, он же идентификатор сообщения у провайдера
 * @param sentAt когда провайдер принял письмо
 */
public record SentDelivery(UUID id, Instant sentAt) {

    public SentDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sentAt, "sentAt");
    }
}
