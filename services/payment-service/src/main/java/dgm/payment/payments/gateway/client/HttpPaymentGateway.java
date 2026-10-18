package dgm.payment.payments.gateway.client;

import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.client.PeerClient;
import dgm.kit.client.PeerRequest;
import dgm.kit.client.PeerResponse;
import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.secret.SecretValue;
import dgm.kit.time.Timestamps;
import dgm.payment.payments.gateway.GatewayPayment;
import dgm.payment.payments.gateway.GatewayRejectedException;
import dgm.payment.payments.gateway.GatewayUnavailableException;
import dgm.payment.payments.gateway.PaymentGateway;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Платёжный шлюз по REST (ADR-015): единственное место, где знают формат шлюза. Создание платежа идёт с {@code Idempotency-Key},
 * равным идентификатору платежа, поэтому клиент каркаса повторяет вызов после сбоя (2 с на попытку, до трёх попыток с паузами 300 и
 * 700 мс, предохранитель 50 % на 10 вызовах с паузой 30 с). Ключ шлюза читается из секрета при каждом вызове и в журнал не попадает.
 *
 * <p>Пакет назван {@code client}: ArchUnit разрешает {@code SecretValue.reveal()} только там, где секрет и нужен.
 */
public final class HttpPaymentGateway implements PaymentGateway {

    private static final String PAYMENTS = "/payment/v1/payments";

    private final Supplier<PeerClient> peer;
    private final Supplier<SecretValue> key;

    /**
     * @param peer клиент шлюза; берётся при первом вызове, поэтому сервис стартует и при недоступном шлюзе
     * @param key  ключ шлюза {@code payment_gateway_key}
     */
    public HttpPaymentGateway(Supplier<PeerClient> peer, Supplier<SecretValue> key) {
        this.peer = peer;
        this.key = key;
    }

    @Override
    public GatewayPayment create(UUID paymentId, UUID orderId, long amount, String currency, String returnUrl, Instant expiresAt) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", currency);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId.toString());
        body.put("amount", money);
        body.put("returnUrl", returnUrl);
        body.put("expiresAt", Timestamps.format(expiresAt));
        PeerResponse response = send(new PeerRequest("POST", PAYMENTS, Json.write(body), paymentId.toString(), headers()));
        Map<String, Object> view = parse(response);
        String id = text(view, "paymentId");
        String payUrl = text(view, "payUrl");
        Object expires = view.get("expiresAt");
        Instant sessionEnd = expires instanceof String text ? Timestamps.parse(text).orElse(null) : null;
        return new GatewayPayment(id, payUrl, sessionEnd);
    }

    private Map<String, String> headers() {
        return Map.of("Authorization", "Bearer " + key.get().reveal());
    }

    private PeerResponse send(PeerRequest request) {
        try {
            return peer.get().call(request);
        } catch (DependencyUnavailableException e) {
            throw new GatewayUnavailableException("Платёжный шлюз не ответил: " + e.reason(), e);
        } catch (DependencyRejectedException e) {
            throw new GatewayRejectedException(e.status(), "Платёжный шлюз отклонил запрос, код " + e.status());
        }
    }

    private static Map<String, Object> parse(PeerResponse response) {
        try {
            return response.json();
        } catch (JsonException e) {
            throw new GatewayUnavailableException("Ответ платёжного шлюза не разбирается как JSON", e);
        }
    }

    private static String text(Map<String, Object> view, String field) {
        Object value = view.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new GatewayUnavailableException("В ответе платёжного шлюза нет поля " + field, null);
        }
        return text;
    }
}
