package dgm.delivery.delivery.poller;

import dgm.delivery.delivery.client.StatusClient;
import dgm.delivery.delivery.domain.ProviderLookup;
import dgm.delivery.delivery.domain.ProviderStatus;
import dgm.delivery.delivery.domain.SentDelivery;
import dgm.delivery.delivery.domain.StatusNotice;
import dgm.delivery.delivery.repository.DeliveryRepository;
import dgm.delivery.delivery.service.DeliveryStatuses;
import dgm.delivery.delivery.service.StatusSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Опрос статусов писем (компонент {@code status-poller}, ADR-011): вторая линия после вебхука. Раз в 5 минут выдачи «отправлено» старше 5 минут
 * без итогового статуса спрашиваются у провайдера, ответ применяется тем же кодом, что вебхук ({@link DeliveryStatuses}). Потеря вебхука почты
 * не оставляет выдачу в «отправлено» навсегда. В {@code fast-time} проход раз в 15 секунд.
 *
 * <p>Выдачи идут по кругу порциями по {@code pollBatch} в порядке {@code (sent_at, id)} с курсором в памяти: если без статуса застряло больше порции,
 * следующий проход берёт следующих, а не опрашивает одних и тех же. Курсор не нужно хранить: потеря его при перезапуске лишь начинает круг заново, а
 * повторный опрос безопасен (статус применяется один раз). Вызовов с транзакцией здесь нет: сначала запрос к провайдеру, потом короткая
 * транзакция применения (правило 9).
 */
public final class StatusPoller {

    private static final Logger LOG = LoggerFactory.getLogger(StatusPoller.class);

    private final DeliveryRepository deliveries;
    private final StatusClient provider;
    private final DeliveryStatuses statuses;
    private final StatusSettings settings;
    private final Clock clock;
    private final MeterRegistry meters;
    private SentDelivery cursor;

    public StatusPoller(DeliveryRepository deliveries, StatusClient provider, DeliveryStatuses statuses, StatusSettings settings, Clock clock,
                        MeterRegistry meters) {
        this.deliveries = deliveries;
        this.provider = provider;
        this.statuses = statuses;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${dgm.delivery.poller.interval:PT5M}", initialDelayString = "${dgm.delivery.poller.initial-delay:PT30S}")
    void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.error("Проход опроса статусов писем завершился ошибкой, повторится в следующий раз", e);
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько статусов применено (доставлено или недоставлено)
     */
    public synchronized int runOnce() {
        Instant now = clock.instant();
        List<SentDelivery> page = deliveries.sentBefore(now.minus(settings.pollAfter()), cursor, settings.pollBatch());
        int applied = 0;
        for (SentDelivery delivery : page) {
            if (poll(delivery, now)) {
                applied++;
            }
        }
        cursor = page.size() < settings.pollBatch() ? null : page.get(page.size() - 1);
        return applied;
    }

    private boolean poll(SentDelivery delivery, Instant now) {
        ProviderLookup answer;
        try {
            answer = provider.statusOf(delivery.id());
        } catch (RuntimeException e) {
            LOG.warn("Опрос статуса письма по выдаче {} не удался: {}", delivery.id(), e.getClass().getSimpleName());
            meters.counter("delivery_status_polls_total", "result", "error").increment();
            return false;
        }
        switch (answer) {
            case ProviderLookup.Unavailable unavailable -> {
                LOG.warn("Провайдер не ответил о статусе письма по выдаче {}: {}", delivery.id(), unavailable.code());
                meters.counter("delivery_status_polls_total", "result", "unavailable").increment();
                return false;
            }
            case ProviderLookup.Unknown unknown -> {
                LOG.warn("Провайдер не знает письма по выдаче {}", delivery.id());
                meters.counter("delivery_status_polls_total", "result", "unknown").increment();
                return false;
            }
            case ProviderLookup.Known known -> {
                meters.counter("delivery_status_polls_total", "result", known.status().wire()).increment();
                if (known.status() != ProviderStatus.DELIVERED && known.status() != ProviderStatus.FAILED) {
                    return false;
                }
                return statuses.apply(new StatusNotice(delivery.id().toString(), known.status(), now, known.reason()))
                        == DeliveryStatuses.Result.APPLIED;
            }
        }
    }
}
