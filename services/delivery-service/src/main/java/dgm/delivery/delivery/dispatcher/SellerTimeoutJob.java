package dgm.delivery.delivery.dispatcher;

import dgm.delivery.delivery.service.SellerRequestOutcomes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Тайм-аут запросов продавцам (компонент {@code seller-timeout-job}, SM-06, SM-01/T10, ADR-028): раз в секунду закрывает запросы, на которые окно ответа
 * вышло, а ответа нет. Закрытие делает {@link SellerRequestOutcomes#expireDue()}: запрос в «тайм-аут», выдача в «ошибка» с причиной
 * {@code seller_unavailable}, событие {@code delivery.seller-timeout} в Outbox одной транзакцией. Запрос с арендой на приём ответа не берётся.
 */
public final class SellerTimeoutJob {

    private static final Logger LOG = LoggerFactory.getLogger(SellerTimeoutJob.class);

    private final SellerRequestOutcomes outcomes;

    public SellerTimeoutJob(SellerRequestOutcomes outcomes) {
        this.outcomes = outcomes;
    }

    @Scheduled(fixedDelayString = "${dgm.delivery.seller.timeout-interval:PT1S}", initialDelayString = "${dgm.delivery.seller.initial-delay:PT5S}")
    void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.error("Проход тайм-аута запросов продавцам завершился ошибкой, повторится в следующий раз", e);
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько запросов закрыто
     */
    public int runOnce() {
        return outcomes.expireDue();
    }
}
