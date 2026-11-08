package dgm.delivery.delivery.repository;

import dgm.delivery.delivery.domain.ExpiredRequest;
import dgm.delivery.delivery.domain.PendingRequest;
import dgm.delivery.delivery.domain.SellerRequestSeed;
import dgm.delivery.delivery.domain.SellerRequestState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Запросы ключа у системы продавца (таблица {@code delivery.seller_request}, компонент {@code delivery-repository}, ADR-028). Значений ключей в таблице
 * нет. Все изменения условные: меняется только запрос в нужном состоянии, поэтому позднее, повторное или проигравшее гонку действие ничего не
 * портит и сообщает об этом результатом {@code false}.
 */
public interface SellerRequestRepository {

    /**
     * Создаёт запрос для первичной выдачи (в одной транзакции с выдачей). Срок отправки наступает сразу.
     *
     * @return {@code true}: запрос создан; {@code false}: у выдачи уже есть запрос
     */
    boolean insert(UUID deliveryId, UUID requestId, SellerRequestSeed seed, Instant deadlineAt, Instant now);

    /**
     * Берёт в аренду до {@code limit} запросов, которые пора отправить: срок следующей отправки сдвигается на {@code lease}, чтобы упавший процесс не
     * потерял запрос, а другой экземпляр его не взял. Вызывать без внешней транзакции.
     */
    List<PendingRequest> claimDue(Instant now, Duration lease, int limit);

    /** Продавец ответил 2xx: «запрошен» в «принят». {@code false}: запрос уже ушёл дальше (ответ продавца обогнал) либо отправка не та. */
    boolean accept(UUID deliveryId, int expectedAttempts, Instant now);

    /** Отправка не удалась, но попытки есть: следующая в {@code nextAttemptAt}; {@code null}: попыток больше нет, запроса ждёт тайм-аут. */
    boolean retry(UUID deliveryId, int expectedAttempts, Instant nextAttemptAt);

    /**
     * «Запрошен» или «принят» в «отказ»: продавец отказал, подключение отключено или адрес не принят. {@code attemptsAfter} это число отправок после
     * этой (пусто: отправки не было).
     */
    boolean fail(UUID deliveryId, Integer attemptsAfter, String errorCode, String errorMessage);

    /** Ключи сохранены: «запрошен» или «принят» в «ответ получен», аренда снимается. */
    boolean answered(UUID requestId, Instant now);

    /** Окно закрылось: «запрошен» или «принят» в «тайм-аут». */
    boolean timeout(UUID deliveryId);

    /** Состояние запроса или пусто, если такого запроса нет. */
    Optional<SellerRequestState> find(UUID requestId);

    /**
     * Берёт аренду на приём ответа: условный {@code UPDATE} по состоянию, сроку и отсутствию чужой действующей аренды. {@code false}: запрос не
     * в состоянии ожидания, срок вышел или аренду уже держит другой ответ.
     */
    boolean lease(UUID requestId, Instant now, Instant leaseUntil);

    /** Снимает аренду (сохранить ключи не удалось, продавец повторит вызов). */
    void release(UUID requestId);

    /**
     * Блокирует в текущей транзакции до {@code limit} запросов в ожидании, срок которых вышел и на которые не действует аренда
     * ({@code FOR UPDATE SKIP LOCKED}: другой экземпляр их пропускает). Вызывать только внутри транзакции.
     */
    List<ExpiredRequest> lockExpired(Instant now, int limit);
}
