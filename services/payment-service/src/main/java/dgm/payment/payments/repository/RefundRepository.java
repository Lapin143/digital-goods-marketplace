package dgm.payment.payments.repository;

import dgm.payment.payments.domain.ManualRefund;
import dgm.payment.payments.domain.RefundAttempt;
import dgm.payment.payments.domain.RefundPayment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Возвраты в базе сервиса: поля возврата платежа ({@code payments.payment}) и попытки ({@code payments.refund_attempt}). Методы изменения
 * вызываются внутри транзакции модуля {@code payments} под блокировкой строки платежа ({@link #lockByOrder}, {@link #lockById}), чтобы
 * вебхук, исполнитель и администратор шли друг за другом (INV-15).
 */
public interface RefundRepository {

    Optional<RefundPayment> findById(UUID paymentId);

    /** Читает платёж заказа и блокирует строку до конца транзакции. */
    Optional<RefundPayment> lockByOrder(UUID orderId);

    /** Читает платёж и блокирует строку до конца транзакции. */
    Optional<RefundPayment> lockById(UUID paymentId);

    /** Записывает причину возврата, пока её нет: так платёж помнит, что возврат запрошен. Платёж не «подтверждён» или причина есть: {@code false}. */
    boolean recordReason(UUID paymentId, String reason);

    /**
     * Вставляет попытку. Открытая попытка по платежу уже есть или возврат принят шлюзом (уникальные индексы INV-15): ничего не вставляется.
     *
     * @return {@code true}, если строка вставлена этим вызовом
     */
    boolean insertAttempt(RefundAttempt attempt, Instant at);

    /**
     * Берёт в аренду до {@code limit} попыток, срок которых наступил: статус становится {@code in_progress}, срок сдвигается на
     * {@code lease}. Вызывается без внешней транзакции. Попытка, чья аренда истекла (процесс упал), наступает снова.
     */
    List<RefundAttempt> claimDue(Instant now, Duration lease, int limit);

    /**
     * Завершает открытую или принимаемую попытку: {@code processing}, {@code succeeded} или {@code failed}. Попытка уже завершена другим
     * путём (вебхук опередил исполнителя): {@code false}, строка не меняется.
     */
    boolean finishAttempt(UUID attemptId, String status, String errorCode, Instant at);

    /** Закрывает открытую и принятую шлюзом попытки платежа как успешные: деньги вернул другой путь. */
    int closeOpenAttempts(UUID paymentId, Instant at);

    /** Записывает внешний идентификатор возврата, пока его нет. */
    boolean attachRefundId(UUID paymentId, String gatewayRefundId);

    /**
     * {@code confirmed → refunded}: время возврата, внешний идентификатор возврата (если задан и ещё не записан), признак очереди
     * администратора снимается. Платёж не «подтверждён»: пусто.
     */
    Optional<RefundPayment> markRefunded(UUID paymentId, String gatewayRefundId, Instant at);

    /** Передаёт возврат администратору: признак очереди и время. Платёж не «подтверждён» или уже в очереди: {@code false}. */
    boolean escalate(UUID paymentId, Instant at);

    /** Сколько попыток возврата сделано по платежу. */
    int attemptCount(UUID paymentId);

    /** Платежи, по которым шлюз принял возврат, а подтверждения нет дольше {@code processingBefore} (сверка возвратов), старые первыми. */
    List<RefundPayment> awaitingConfirmation(Instant processingBefore, int limit);

    /** Очередь ручных возвратов: старые выше; {@code after} это последняя строка предыдущей страницы. */
    List<ManualRefund> queue(Optional<QueueCursor> after, int limit);

    /** Строка, после которой продолжается очередь (порядок «время передачи, идентификатор платежа»). */
    record QueueCursor(Instant escalatedAt, UUID paymentId) {
    }
}
