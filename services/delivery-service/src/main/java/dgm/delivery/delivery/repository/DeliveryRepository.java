package dgm.delivery.delivery.repository;

import dgm.delivery.delivery.domain.DeliveryState;
import dgm.delivery.delivery.domain.DueWatch;
import dgm.delivery.delivery.domain.QueuedDelivery;
import dgm.delivery.delivery.domain.SentDelivery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Единственный путь записи в таблицы выдачи (компонент {@code delivery-repository}). Значений ключей в таблицах нет, поэтому их нет и в
 * методах. Исходы попыток пишутся сравнением числа выполненных попыток: поздний или повторный исход (аренда истекла, другой экземпляр уже
 * записал свой) ничего не меняет и сообщает об этом результатом {@code false}.
 */
public interface DeliveryRepository {

    /**
     * Создаёт первичную выдачу в очереди (SM-06/T1). Вторая первичная выдача заказа невозможна (INV-18): повторное {@code order.paid} даёт
     * {@code false}, строка не добавляется.
     */
    boolean insertPrimary(UUID id, UUID orderId, UUID buyerId, String address, String productTitle, int quantity, Instant now);

    /** Открывает контроль доставки заказа (одна запись на заказ). Повтор даёт {@code false}. */
    boolean insertWatch(UUID orderId, Instant deadline, Instant now);

    /**
     * Берёт в аренду до {@code limit} выдач, срок которых наступил: срок следующей попытки сдвигается на {@code lease}, чтобы упавший процесс
     * не терял выдачу, а другой экземпляр её не взял. Вызывать без внешней транзакции.
     */
    List<QueuedDelivery> claimDue(Instant now, Duration lease, int limit);

    /** T2: провайдер принял письмо. Записывает и попытку с номером на единицу больше {@code expectedAttempts}. */
    boolean markSent(UUID id, int expectedAttempts, Instant startedAt, Instant finishedAt);

    /** T3: повторяемая ошибка, следующая попытка в {@code nextAttemptAt}. Записывает и попытку. */
    boolean markRetry(UUID id, int expectedAttempts, String errorCode, Instant nextAttemptAt, Instant startedAt, Instant finishedAt);

    /** T4: выдача в «ошибку» после окончательной ошибки либо шестой неудачи. Записывает и попытку. */
    boolean markFailed(UUID id, int expectedAttempts, String outcome, String errorCode, String failReason, Instant startedAt, Instant finishedAt);

    /**
     * INV-44: адрес в открытых выдачах покупателя (в очереди и отправленных) заменяется. Закрытые («доставлено», «ошибка») не меняются.
     *
     * @return сколько выдач изменено
     */
    int anonymizeOpen(UUID buyerId, String replacement);

    /** Состояние выдачи или пусто, если такой выдачи нет. */
    Optional<DeliveryState> find(UUID id);

    /** Первичная выдача заказа или пусто. */
    Optional<DeliveryState> primaryOf(UUID orderId);

    /**
     * Записывает статус письма от провайдера (дедупликация по паре «выдача, статус», ADR-006).
     *
     * @return {@code true}: статус новый; {@code false}: такой уже принят, повтор ничего не меняет
     */
    boolean recordProviderStatus(UUID deliveryId, String providerStatus, Instant occurredAt, Instant now);

    /** T5: «отправлено» в «доставлено», время доставки по данным провайдера. {@code false}: выдача уже не «отправлено». */
    boolean markDelivered(UUID id, Instant deliveredAt);

    /** T6: «отправлено» в «ошибка» по сообщению провайдера о недоставке. {@code false}: выдача уже не «отправлено». */
    boolean markUndelivered(UUID id, String failReason, String errorCode);

    /** Срок контроля заказа (момент оплаты плюс окно) или пусто. */
    Optional<Instant> watchDeadline(UUID orderId);

    /** Закрывает контроль заказа: из «открыт» и из «передан в поддержку». {@code false}: контроль уже закрыт либо его нет. */
    boolean closeWatch(UUID orderId, Instant now);

    /**
     * Блокирует в текущей транзакции до {@code limit} открытых контролей, срок которых наступил ({@code FOR UPDATE SKIP LOCKED}: другой
     * экземпляр сервиса их пропускает). Вызывать только внутри транзакции.
     */
    List<DueWatch> lockDueWatches(Instant now, int limit);

    /** «Открыт» в «передан в поддержку». {@code false}: контроль уже не открыт. */
    boolean handOver(UUID orderId, Instant now);

    /**
     * Выдачи «отправлено», которым пора спросить статус у провайдера: письмо принято не позже {@code sentBefore}, порядок {@code (sent_at, id)}, после
     * курсора {@code after} (пусто: с начала).
     */
    List<SentDelivery> sentBefore(Instant sentBefore, SentDelivery after, int limit);
}
