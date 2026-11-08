package dgm.delivery.delivery;

import dgm.delivery.delivery.client.SellerCalls;
import dgm.delivery.delivery.client.SellerEndpoints;
import dgm.delivery.delivery.domain.PendingRequest;
import dgm.delivery.delivery.domain.SellerCallResult;
import dgm.delivery.delivery.domain.SellerEndpoint;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.secret.SecretValue;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Система продавца и каталог подключений в процессе теста: на месте {@code SellerApiClient} и {@code SellerEndpointClient} стендового сервиса (бины с
 * {@code @Primary}, см. {@code DeliveryStandIT}). Запись принятых запросов, сценарий исходов отправки и отказ каталога под управлением теста. Подпись,
 * защиту от SSRF и настоящий приёмник заглушки {@code external-stubs} проверяют модульные тесты {@code SellerApiClientTest} и сквозной сценарий К15; здесь
 * важно, что делает сервис с исходом отправки (база, события, письмо), а не как байты уходят по сети.
 */
final class SellerSystem implements SellerCalls, SellerEndpoints {

    /** Отправленный запрос: то, что уходит в тело, без секрета и адреса. */
    record Sent(UUID requestId, UUID orderId, UUID sellerId, UUID productId, long orderNumber, int quantity, Instant deadlineAt, int attempts, String url) {
    }

    private final Map<UUID, SellerEndpoint> endpoints = new ConcurrentHashMap<>();
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<SellerCallResult> script = new ConcurrentLinkedDeque<>();
    private volatile boolean catalogDown;

    /** Действующее подключение продавца. */
    void connect(UUID sellerId) {
        endpoints.put(sellerId, new SellerEndpoint("https://seller.example:8443/seller/" + sellerId + "/request", "active", 1));
    }

    /** Подключение продавца в другом статусе (например, {@code disabled}). */
    void connect(UUID sellerId, String status) {
        endpoints.put(sellerId, new SellerEndpoint("https://seller.example:8443/seller/" + sellerId + "/request", status, 1));
    }

    /** Следующие исходы отправки по порядку; когда сценарий пуст, система продавца отвечает «принято». */
    void then(SellerCallResult... results) {
        for (SellerCallResult result : results) {
            script.addLast(result);
        }
    }

    /** У продавца подключения нет (каталог отвечает 404). */
    void forget(UUID sellerId) {
        endpoints.remove(sellerId);
    }

    void catalogDown(boolean down) {
        catalogDown = down;
    }

    void reset() {
        endpoints.clear();
        sent.clear();
        script.clear();
        catalogDown = false;
    }

    List<Sent> sent(UUID orderId) {
        return sent.stream().filter(s -> orderId.equals(s.orderId())).toList();
    }

    @Override
    public SellerCallResult send(String url, SecretValue secret, PendingRequest request) {
        sent.add(new Sent(request.requestId(), request.orderId(), request.sellerId(), request.productId(), request.orderNumber(), request.quantity(),
                request.deadlineAt(), request.attempts(), url));
        SellerCallResult next = script.pollFirst();
        return next == null ? new SellerCallResult.Accepted() : next;
    }

    @Override
    public Optional<SellerEndpoint> find(UUID sellerId) {
        if (catalogDown) {
            throw new DependencyUnavailableException("catalog-service", DependencyUnavailableException.TIMEOUT, 0, null);
        }
        return Optional.ofNullable(endpoints.get(sellerId));
    }
}
