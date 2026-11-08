package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.PendingRequest;
import dgm.delivery.delivery.domain.SellerCallResult;
import dgm.kit.client.EndpointGuard;
import dgm.kit.client.SellerCaller;
import dgm.kit.json.Json;
import dgm.kit.secret.SecretValue;
import dgm.kit.time.Timestamps;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент системы продавца (компонент {@code seller-api-client}, ADR-028, раздел 2): один подписанный {@code POST} с запросом выдачи через
 * {@link SellerCaller} (проверка адреса перед каждым вызовом, подпись, тайм-аут 5 секунд, ответ до 64 КБ, без перенаправлений). Повторы и состояние запроса
 * ведёт вызывающий. В теле запроса {@code requestId}, {@code orderNumber}, {@code productId}, {@code quantity} и {@code respondBy}; покупателя, его адреса и
 * e-mail нет (INV-44). В журнал идут идентификаторы, адрес продавца и тело ответа не попадают.
 */
public final class SellerApiClient implements SellerCalls {

    private static final Logger LOG = LoggerFactory.getLogger(SellerApiClient.class);

    private final SellerCaller caller;

    public SellerApiClient(SellerCaller caller) {
        this.caller = caller;
    }

    @Override
    public SellerCallResult send(String url, SecretValue secret, PendingRequest request) {
        byte[] body = Json.write(body(request)).getBytes(StandardCharsets.UTF_8);
        try {
            SellerCaller.Response response = caller.post(url, body, secret, Map.of("X-Request-Id", request.requestId().toString()));
            if (response.success()) {
                return new SellerCallResult.Accepted();
            }
            if (response.status() >= 500) {
                LOG.warn("Система продавца {} ответила {} на запрос {}", request.sellerId(), response.status(), request.requestId());
                return new SellerCallResult.Retryable("seller_http_" + response.status());
            }
            LOG.warn("Система продавца {} отказала кодом {} на запрос {}", request.sellerId(), response.status(), request.requestId());
            return new SellerCallResult.Rejected(response.status());
        } catch (EndpointGuard.RejectedException e) {
            LOG.warn("Адрес системы продавца {} не прошёл проверку перед запросом {}", request.sellerId(), request.requestId());
            return new SellerCallResult.EndpointRejected();
        } catch (SellerCaller.CallException e) {
            LOG.warn("Запрос {} продавцу {} не состоялся: {}", request.requestId(), request.sellerId(), e.kind());
            return new SellerCallResult.Retryable("seller_" + e.kind().name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** Тело запроса по описанию в ADR-028: без данных покупателя. */
    static Map<String, Object> body(PendingRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", request.requestId().toString());
        body.put("orderNumber", request.orderNumber());
        body.put("productId", request.productId().toString());
        body.put("quantity", request.quantity());
        body.put("respondBy", Timestamps.format(request.deadlineAt()));
        return body;
    }
}
