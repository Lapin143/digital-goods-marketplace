package dgm.delivery.delivery.service;

import dgm.delivery.delivery.domain.ExpiredRequest;
import dgm.delivery.delivery.domain.PendingRequest;
import dgm.delivery.delivery.domain.SellerRequestState;
import dgm.delivery.delivery.repository.DeliveryRepository;
import dgm.delivery.delivery.repository.SellerRequestRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Запись исходов запроса продавцу (SM-06, ADR-028, шаг 12): ответ на отправку, отказ и закрытие окна. Каждое изменение условное, поэтому запись,
 * которая проиграла гонку (продавец успел ответить, или запрос взял другой экземпляр), ничего не портит и возвращает {@code false}.
 *
 * <p>Закрытие запроса ошибкой ({@link #fail}) и по тайм-ауту ({@link #expireDue}) идёт одной транзакцией: запрос, выдача «ошибка» с причиной
 * {@code seller_unavailable}, контроль доставки и событие {@code delivery.seller-timeout} в Outbox. Событие {@code delivery.failed} здесь не
 * публикуется: письма не было, а заказ по этому событию отменяет {@code order-service} (T10). Вызовов соседей внутри транзакции нет (правило 9).
 * В журнал идут идентификаторы и код, адреса продавца и текста его отказа там нет.
 */
public final class SellerRequestOutcomes {

    private static final Logger LOG = LoggerFactory.getLogger(SellerRequestOutcomes.class);

    /** Причина события при молчании продавца и при отказе. */
    static final String REASON_TIMEOUT = "timeout";
    static final String REASON_SELLER_ERROR = "seller_error";
    /** Код ошибки выдачи при молчании продавца. */
    static final String CODE_TIMEOUT = "seller_timeout";
    /** Коды отказа, которые присылает сам продавец в ответе (остальные коды ставит платформа). */
    static final java.util.Set<String> CODE_FAILED_BY_SELLER = java.util.Set.of("out_of_stock", "cannot_issue", "other");

    private final SellerRequestRepository requests;
    private final DeliveryRepository deliveries;
    private final DeliveryTransaction transaction;
    private final SellerSettings settings;
    private final Clock clock;
    private final MeterRegistry meters;

    public SellerRequestOutcomes(SellerRequestRepository requests, DeliveryRepository deliveries, DeliveryTransaction transaction, SellerSettings settings,
                                 Clock clock, MeterRegistry meters) {
        this.requests = requests;
        this.deliveries = deliveries;
        this.transaction = transaction;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Продавец ответил на запрос кодом 2xx: «запрошен» в «принят». Ключи придут отдельным вызовом продавца.
     *
     * @return {@code false}: запрос уже ушёл дальше (ответ продавца с ключами обогнал подтверждение) либо отправка не та
     */
    public boolean accepted(PendingRequest request) {
        boolean applied = requests.accept(request.deliveryId(), request.attempts(), clock.instant());
        meters.counter("delivery_seller_requests_total", "outcome", applied ? "accepted" : "stale").increment();
        if (!applied) {
            LOG.info("Подтверждение продавца по запросу {} не записано: запрос уже изменён (ответ с ключами мог прийти раньше)", request.requestId());
        }
        return applied;
    }

    /**
     * Отправка не удалась временно (5xx, тайм-аут, обрыв): считается использованной, следующая через 5 секунд после первой неудачи и через 30 после
     * второй. После третьей запроса ждёт тайм-аут. Следующая отправка не назначается за срок ответа: она всё равно не успела бы.
     */
    public boolean retry(PendingRequest request, String code) {
        Duration pause = SellerSettings.pauseAfter(request.nextAttemptNo());
        Instant next = pause == null ? null : clock.instant().plus(pause);
        if (next != null && !next.isBefore(request.deadlineAt())) {
            next = null;
        }
        boolean applied = requests.retry(request.deliveryId(), request.attempts(), next);
        meters.counter("delivery_seller_requests_total", "outcome", applied ? "retryable_error" : "stale").increment();
        if (!applied) {
            LOG.info("Исход отправки {} запроса {} не записан: запрос уже изменён", request.nextAttemptNo(), request.requestId());
        } else {
            LOG.warn("Запрос {} продавцу {}: отправка {} не удалась ({}), {}", request.requestId(), request.sellerId(), request.nextAttemptNo(), code,
                    next == null ? "повторов нет, ждём тайм-аут" : "повтор в " + next);
        }
        return applied;
    }

    /**
     * Отказ без повтора: продавец ответил 4xx (код {@code seller_rejected}), подключения нет ({@code integration_missing}), оно отключено
     * ({@code integration_disabled}) или адрес не прошёл проверку ({@code endpoint_rejected}). Заказ отменяется так же, как при молчании.
     *
     * @param sent {@code true}: эта отправка состоялась и учитывается в числе попыток; {@code false}: запрос не уходил
     * @return {@code false}: запрос уже в другом состоянии
     */
    public boolean fail(PendingRequest request, String code, boolean sent) {
        Instant now = clock.instant();
        boolean applied = transaction.execute(() -> {
            if (!requests.fail(request.deliveryId(), sent ? request.nextAttemptNo() : null, code, null)) {
                return false;
            }
            cancelDelivery(request.deliveryId(), request.orderId(), request.sellerId(), request.createdAt(), code, REASON_SELLER_ERROR, now);
            return true;
        });
        meters.counter("delivery_seller_requests_total", "outcome", applied ? "failed" : "stale").increment();
        if (applied) {
            meters.counter("delivery_seller_timeouts_total", "reason", REASON_SELLER_ERROR).increment();
            LOG.warn("Запрос {} продавцу {} закрыт отказом {}, заказ {} будет отменён", request.requestId(), request.sellerId(), code, request.orderId());
        } else {
            LOG.info("Отказ {} по запросу {} не записан: запрос уже изменён", code, request.requestId());
        }
        return applied;
    }

    /**
     * Ключи продавца сохранены в хранилище ключей: запрос становится «ответ получен», аренда снимается, диспетчер вправе взять выдачу в отправку
     * письма. Условный {@code UPDATE}: проигравший гонку ничего не меняет.
     *
     * @return {@code false}: запрос уже в другом состоянии (аренда не давала этого случиться, значит нарушение согласованности)
     */
    public boolean answered(UUID requestId) {
        boolean applied = requests.answered(requestId, clock.instant());
        meters.counter("delivery_seller_requests_total", "outcome", applied ? "answered" : "stale").increment();
        if (!applied) {
            LOG.warn("Ответ по запросу {} не записан: запрос уже в другом состоянии", requestId);
        }
        return applied;
    }

    /**
     * Продавец сам сообщил, что выдать не может ({@code error} в ответе): запрос закрывается отказом с его кодом, заказ отменяется так же, как при
     * молчании. Пояснение продавца уходит только в строку запроса для оператора поддержки, в журнал и события оно не попадает.
     *
     * @param code    код отказа продавца (out_of_stock, cannot_issue, other)
     * @param message пояснение продавца или {@code null}
     * @return {@code false}: запрос уже в другом состоянии
     */
    public boolean failedByAnswer(SellerRequestState request, String code, String message) {
        Instant now = clock.instant();
        boolean applied = transaction.execute(() -> {
            if (!requests.fail(request.deliveryId(), null, code, message)) {
                return false;
            }
            cancelDelivery(request.deliveryId(), request.orderId(), request.sellerId(), request.createdAt(), code, REASON_SELLER_ERROR, now);
            return true;
        });
        meters.counter("delivery_seller_requests_total", "outcome", applied ? "failed" : "stale").increment();
        if (applied) {
            meters.counter("delivery_seller_timeouts_total", "reason", REASON_SELLER_ERROR).increment();
            LOG.warn("Продавец {} отказал по запросу {} кодом {}, заказ {} будет отменён", request.sellerId(), request.requestId(), code, request.orderId());
        } else {
            LOG.info("Отказ продавца по запросу {} не записан: запрос уже изменён", request.requestId());
        }
        return applied;
    }

    /**
     * Закрывает запросы, на которые окно ответа вышло, а ответ не пришёл (SM-06, T10): пачкой, под {@code FOR UPDATE SKIP LOCKED}, одной транзакцией.
     * Запрос с действующей арендой (ответ продавца принят до конца окна и сохраняет ключи) не берётся, ему даётся дойти до конца (Р-190).
     *
     * @return сколько запросов закрыто
     */
    public int expireDue() {
        Instant now = clock.instant();
        int closed = transaction.execute(() -> {
            List<ExpiredRequest> expired = requests.lockExpired(now, settings.timeoutBatch());
            int count = 0;
            for (ExpiredRequest request : expired) {
                if (requests.timeout(request.deliveryId())) {
                    cancelDelivery(request.deliveryId(), request.orderId(), request.sellerId(), request.createdAt(), CODE_TIMEOUT, REASON_TIMEOUT, now);
                    count++;
                }
            }
            return count;
        });
        if (closed > 0) {
            meters.counter("delivery_seller_timeouts_total", "reason", REASON_TIMEOUT).increment(closed);
            LOG.warn("Закрыто запросов продавцам по тайм-ауту: {}", closed);
        }
        return closed;
    }

    /** Выдача «ошибка» без письма, контроль закрыт (отдельное оповещение не нужно, заказ отменяется), событие в Outbox. Только внутри транзакции. */
    private void cancelDelivery(UUID deliveryId, UUID orderId, UUID sellerId, Instant createdAt, String errorCode, String reason, Instant now) {
        if (!deliveries.markSellerUnavailable(deliveryId, errorCode)) {
            // выдача не в очереди: запрос продавцу не мог бы остаться открытым (диспетчер такую выдачу не берёт), нарушение согласованности
            meters.counter("delivery_anomalies_total", "reason", "seller_request_delivery_not_queued").increment();
            throw new IllegalStateException("Выдача " + deliveryId + " запроса продавцу не в очереди: закрыть её по ошибке продавца нельзя");
        }
        deliveries.closeWatch(orderId, now);
        long waited = Duration.between(createdAt, now).toSeconds();
        transaction.publish(DeliveryEvents.TOPIC, DeliveryEvents.AGGREGATE, orderId.toString(), DeliveryEvents.SELLER_TIMEOUT, DeliveryEvents.SCHEMA_VERSION,
                DeliveryEvents.sellerTimeout(deliveryId, orderId, sellerId, waited, reason));
    }
}
