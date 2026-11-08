package dgm.delivery.delivery.dispatcher;

import dgm.delivery.delivery.client.SellerCalls;
import dgm.delivery.delivery.client.SellerEndpoints;
import dgm.delivery.delivery.domain.PendingRequest;
import dgm.delivery.delivery.domain.SellerCallResult;
import dgm.delivery.delivery.domain.SellerEndpoint;
import dgm.delivery.delivery.repository.SellerRequestRepository;
import dgm.delivery.delivery.service.SellerRequestOutcomes;
import dgm.delivery.delivery.service.SellerSettings;
import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.crypto.SellerSecrets;
import dgm.kit.secret.SecretValue;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Отправка запросов системам продавцов (компонент {@code seller-request-job}, ADR-028, шаг 12): раз в секунду берёт в аренду до 20 запросов
 * «запрошен», срок отправки которых наступил ({@code FOR UPDATE SKIP LOCKED}, два экземпляра один запрос не возьмут), и для каждого <b>вне
 * транзакции</b> (правило 9) получает у каталога адрес и версию секрета, подписывает и отправляет запрос. Исход записывается отдельной короткой записью
 * ({@link SellerRequestOutcomes}).
 *
 * <p>Каталог недоступен или ответил неразборчиво: попытка не тратится, запрос возвращается в очередь по окончании аренды (минута) и уйдёт позже, пока
 * не вышло окно ответа. Продавец не подключён или подключение отключено: запрос закрывается отказом без отправки. Упавший процесс не теряет запрос:
 * аренда истекает, запрос берёт другой экземпляр.
 */
public final class SellerRequestJob {

    private static final Logger LOG = LoggerFactory.getLogger(SellerRequestJob.class);

    private final SellerRequestRepository requests;
    private final SellerEndpoints endpoints;
    private final SellerCalls api;
    private final SellerSecrets secrets;
    private final SellerRequestOutcomes outcomes;
    private final SellerSettings settings;
    private final Clock clock;

    public SellerRequestJob(SellerRequestRepository requests, SellerEndpoints endpoints, SellerCalls api, SellerSecrets secrets,
                            SellerRequestOutcomes outcomes, SellerSettings settings, Clock clock) {
        this.requests = requests;
        this.endpoints = endpoints;
        this.api = api;
        this.secrets = secrets;
        this.outcomes = outcomes;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${dgm.delivery.seller.interval:PT1S}", initialDelayString = "${dgm.delivery.seller.initial-delay:PT5S}")
    void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.error("Проход отправки запросов продавцам завершился ошибкой, повторится в следующий раз", e);
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько запросов взято в работу
     */
    public int runOnce() {
        List<PendingRequest> due = requests.claimDue(clock.instant(), settings.sendLease(), settings.batch());
        due.stream().sorted(Comparator.comparing(PendingRequest::deliveryId)).forEach(this::dispatch);
        return due.size();
    }

    private void dispatch(PendingRequest request) {
        try {
            if (!clock.instant().isBefore(request.deadlineAt())) {
                // окно вышло: запрос закроет исполнитель тайм-аута, отправлять уже незачем
                return;
            }
            Optional<SellerEndpoint> endpoint;
            try {
                endpoint = endpoints.find(request.sellerId());
            } catch (DependencyUnavailableException | DependencyRejectedException e) {
                // каталог недоступен: попытка не тратится, запрос вернётся в очередь по окончании аренды
                LOG.warn("Каталог не дал подключение продавца {} для запроса {}: {}", request.sellerId(), request.requestId(), e.getMessage());
                return;
            }
            if (endpoint.isEmpty()) {
                outcomes.fail(request, "integration_missing", false);
                return;
            }
            if (!endpoint.get().active()) {
                outcomes.fail(request, "integration_disabled", false);
                return;
            }
            record(request, send(request, endpoint.get()));
        } catch (RuntimeException e) {
            // исход не записан: запрос остаётся «запрошен» с арендой и наступит снова, когда аренда истечёт
            LOG.error("Отправка {} запроса {} не завершена, повторится после аренды", request.nextAttemptNo(), request.requestId(), e);
        }
    }

    private SellerCallResult send(PendingRequest request, SellerEndpoint endpoint) {
        SecretValue secret = secrets.secret(request.sellerId(), endpoint.secretVersion());
        try {
            return api.send(endpoint.url(), secret, request);
        } finally {
            secret.destroy();
        }
    }

    private void record(PendingRequest request, SellerCallResult result) {
        switch (result) {
            case SellerCallResult.Accepted accepted -> outcomes.accepted(request);
            case SellerCallResult.Retryable retryable -> outcomes.retry(request, retryable.code());
            case SellerCallResult.Rejected rejected -> outcomes.fail(request, "seller_rejected", true);
            case SellerCallResult.EndpointRejected rejected -> outcomes.fail(request, "endpoint_rejected", false);
        }
    }
}
