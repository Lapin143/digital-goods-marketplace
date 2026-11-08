package dgm.platform.notification.service;

import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.crypto.SellerSecrets;
import dgm.kit.secret.SecretValue;
import dgm.platform.notification.client.SellerEndpoints;
import dgm.platform.notification.client.WebhookCalls;
import dgm.platform.notification.domain.PendingWebhook;
import dgm.platform.notification.domain.RetryPolicy;
import dgm.platform.notification.domain.SellerEndpoint;
import dgm.platform.notification.domain.WebhookCallResult;
import dgm.platform.notification.repository.WebhookDeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Отправка webhook продавцам (компонент {@code seller-webhook-job}, Р-193): раз в секунду берёт в аренду до {@code batch} уведомлений, срок которых
 * наступил ({@code FOR UPDATE SKIP LOCKED}, два экземпляра одно уведомление не возьмут), и для каждого <b>вне транзакции</b> (правило 9) получает у
 * каталога адрес и версию секрета, подписывает и отправляет запрос. Исход записывается отдельной короткой записью.
 *
 * <p>Исходы одной попытки: получатель ответил 2xx, статус {@code sent}; любой другой исход (4xx, 5xx, тайм-аут, обрыв, адрес не прошёл проверку) это
 * неудача: пауза по лестнице 30 секунд, 2, 10, 30 минут, 2 часа, после шестой неудачи статус {@code failed}. Продавец не подключён или подключение
 * отключено: статус {@code skipped}, попыток не было. Каталог недоступен: попытка не тратится, уведомление вернётся в очередь по окончании аренды.
 * Идентификатор события в заголовке {@code X-Event-Id} одинаков во всех попытках, получатель сам отсекает дубли (хотя бы одна доставка, ADR-011).
 *
 * <p>Медленный или неисправный получатель не тормозит остальных сверх своего тайм-аута: вызов ограничен 5 секундами ({@code SellerCaller}), письма
 * и другие уведомления идут своими очередями, число попыток конечно.
 */
public final class WebhookDispatcher {

    static final String INTEGRATION_MISSING = "integration_missing";
    static final String INTEGRATION_DISABLED = "integration_disabled";

    private static final Logger LOG = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookDeliveryRepository deliveries;
    private final SellerEndpoints endpoints;
    private final WebhookCalls calls;
    private final SellerSecrets secrets;
    private final RetryPolicy retries;
    private final Duration lease;
    private final int batch;
    private final Clock clock;
    private final MeterRegistry metrics;

    public WebhookDispatcher(WebhookDeliveryRepository deliveries, SellerEndpoints endpoints, WebhookCalls calls, SellerSecrets secrets,
                             RetryPolicy retries, Duration lease, int batch, Clock clock, MeterRegistry metrics) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.calls = calls;
        this.secrets = secrets;
        this.retries = retries;
        this.lease = lease;
        this.batch = batch;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Просмотр очереди по расписанию. Ошибка одного просмотра не останавливает расписание. */
    @Scheduled(fixedDelayString = "${dgm.notification.webhook.interval:PT1S}", initialDelayString = "${dgm.notification.webhook.initial-delay:PT5S}")
    void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.error("Проход отправки webhook завершился ошибкой, повторится в следующий раз", e);
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько уведомлений взято в работу
     */
    public int runOnce() {
        List<PendingWebhook> due = deliveries.claimDue(clock.instant(), lease, batch);
        due.forEach(this::dispatch);
        return due.size();
    }

    private void dispatch(PendingWebhook webhook) {
        try {
            Optional<SellerEndpoint> endpoint;
            try {
                endpoint = endpoints.find(webhook.sellerId());
            } catch (DependencyUnavailableException | DependencyRejectedException e) {
                // каталог недоступен: попытка не тратится, уведомление вернётся в очередь по окончании аренды
                LOG.warn("Каталог не дал подключение продавца {} для уведомления {}: {}", webhook.sellerId(), webhook.id(), e.getMessage());
                return;
            }
            if (endpoint.isEmpty()) {
                skip(webhook, INTEGRATION_MISSING);
                return;
            }
            if (!endpoint.get().active()) {
                skip(webhook, INTEGRATION_DISABLED);
                return;
            }
            record(webhook, send(webhook, endpoint.get()));
        } catch (RuntimeException e) {
            // исход не записан: уведомление остаётся в очереди с арендой и наступит снова, когда аренда истечёт
            LOG.error("Отправка {} уведомления {} не завершена, повторится после аренды", webhook.nextAttemptNo(), webhook.id(), e);
        }
    }

    private WebhookCallResult send(PendingWebhook webhook, SellerEndpoint endpoint) {
        SecretValue secret = secrets.secret(webhook.sellerId(), endpoint.secretVersion());
        try {
            return calls.send(endpoint.url(), secret, webhook);
        } finally {
            secret.destroy();
        }
    }

    private void skip(PendingWebhook webhook, String reason) {
        if (deliveries.markSkipped(webhook.id(), reason)) {
            count("skipped");
            LOG.info("Уведомление {} пропущено: {}", webhook.id(), reason);
        }
    }

    private void record(PendingWebhook webhook, WebhookCallResult result) {
        int attempt = webhook.nextAttemptNo();
        Instant now = clock.instant();
        if (result.accepted()) {
            if (deliveries.markSent(webhook.id(), attempt, result.responseCode(), now)) {
                count("sent");
                LOG.info("Уведомление {} ({}) принято системой продавца с попытки {}", webhook.id(), webhook.eventType(), attempt);
            }
            return;
        }
        Optional<Duration> delay = retries.delayAfter(attempt);
        if (delay.isEmpty()) {
            if (deliveries.markFailed(webhook.id(), attempt, result.responseCode(), result.errorCode(), now)) {
                count("failed");
                LOG.warn("Уведомление {} ({}) не доставлено после {} попыток: {}", webhook.id(), webhook.eventType(), attempt, result.errorCode());
            }
            return;
        }
        if (deliveries.reschedule(webhook.id(), attempt, now.plus(delay.get()), result.responseCode(), result.errorCode(), now)) {
            count("retry");
            LOG.warn("Уведомление {} ({}): попытка {} не удалась ({}), повтор через {} с", webhook.id(), webhook.eventType(), attempt, result.errorCode(),
                    delay.get().toSeconds());
        }
    }

    private void count(String result) {
        metrics.counter("webhooks_total", "result", result).increment();
    }
}
