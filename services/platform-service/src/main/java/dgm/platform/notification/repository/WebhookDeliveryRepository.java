package dgm.platform.notification.repository;

import dgm.platform.notification.domain.NewWebhookDelivery;
import dgm.platform.notification.domain.PendingWebhook;
import dgm.platform.notification.domain.WebhookDelivery;
import dgm.platform.notification.domain.WebhookDeliveryFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Очередь webhook продавцам ({@code notification.webhook_delivery}, Р-193). Интерфейс нужен, чтобы обработчик события и отправитель проверялись
 * модульными тестами без базы.
 */
public interface WebhookDeliveryRepository {

    /**
     * Ставит уведомление в очередь, срок первой попытки сейчас. Повторная обработка того же события ничего не добавляет (уникальный
     * {@code event_id}, ADR-006).
     *
     * @return {@code true}, если строка добавлена; {@code false}, если по этому событию уведомление уже есть
     */
    boolean insert(NewWebhookDelivery delivery);

    /**
     * Берёт в аренду до {@code limit} уведомлений, срок которых наступил ({@code FOR UPDATE SKIP LOCKED}, два экземпляра одно уведомление не возьмут).
     * Аренда сдвигает срок следующей попытки вперёд: упавший экземпляр не теряет уведомление, оно наступит снова. Вызывать без внешней транзакции.
     */
    List<PendingWebhook> claimDue(Instant now, Duration lease, int limit);

    /** Получатель ответил 2xx: статус {@code sent}. {@code false}, если уведомление уже не в очереди. */
    boolean markSent(UUID id, int attempts, int responseCode, Instant at);

    /** Попытка не удалась, есть следующая: остаётся в очереди со сроком {@code nextAttemptAt}. */
    boolean reschedule(UUID id, int attempts, Instant nextAttemptAt, Integer responseCode, String errorCode, Instant at);

    /** Все попытки исчерпаны: статус {@code failed}. Попыток при этом ровно шесть (ограничение базы). */
    boolean markFailed(UUID id, int attempts, Integer responseCode, String errorCode, Instant at);

    /** У продавца нет действующего подключения: статус {@code skipped}, попыток не было. */
    boolean markSkipped(UUID id, String reason);

    /** Уведомления продавца, новые выше; на одну строку больше размера страницы, лишняя строка говорит, что страница не последняя. */
    List<WebhookDelivery> findBySeller(WebhookDeliveryFilter filter);
}
