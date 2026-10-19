package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.AttemptResult;
import dgm.delivery.delivery.letter.Letter;
import java.util.UUID;

/** Клиент e-mail-провайдера (компонент {@code email-client}, адаптер): «передать письмо» и перевод ответа провайдера в исход попытки. */
public interface EmailClient {

    /**
     * Передаёт письмо провайдеру напрямую, мимо Kafka и {@code platform-service}. Идентификатор сообщения равен идентификатору выдачи: провайдер
     * распознает повтор после упавшего процесса (ADR-006). Ошибки не бросает: любой исход это {@link AttemptResult}.
     */
    AttemptResult send(UUID messageId, String to, Letter letter);
}
