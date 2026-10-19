package dgm.order.orders.client;

import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.client.PeerClient;
import dgm.kit.client.PeerRequest;
import dgm.kit.idempotency.IdempotencyKeys;
import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.time.Timestamps;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PaymentClient} поверх {@code PeerClient}: {@code POST /internal/v1/payment-sessions} по mTLS. Повторов нет (их делает сервис платежей), ключ
 * идемпотентности выведен из шага и заказа. Ответ 4xx это отказ по смыслу ({@link Refused}), 5xx и молчание это недоступность соседа.
 */
public final class PeerPaymentClient implements PaymentClient {

    /** Имя соседа в {@code dgm.peers.*}. */
    public static final String PEER = "payment-service";

    /** Имя шага для ключа идемпотентности (Р-149). */
    public static final String STEP = "open-session";

    private static final Logger LOG = LoggerFactory.getLogger(PeerPaymentClient.class);

    private final Supplier<PeerClient> peer;

    public PeerPaymentClient(Supplier<PeerClient> peer) {
        this.peer = peer;
    }

    @Override
    public SessionResult open(UUID orderId, long amount, String currency, Instant sessionExpiresAt) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", currency);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId.toString());
        body.put("amount", money);
        body.put("sessionExpiresAt", Timestamps.format(sessionExpiresAt));
        try {
            Map<String, Object> json = peer.get()
                    .call(PeerRequest.post("/internal/v1/payment-sessions", Json.write(body), IdempotencyKeys.derived(STEP, orderId).toString())).json();
            String url = (String) json.get("sessionUrl");
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("В ответе нет адреса страницы оплаты");
            }
            return new Opened(url, Timestamps.parse((String) json.get("sessionExpiresAt")).orElseThrow());
        } catch (DependencyRejectedException e) {
            LOG.warn("Сервис платежей отказал в сессии для заказа {}: код {} {}", orderId, e.status(), e.problemCode());
            return new Refused(e.status());
        } catch (JsonException | ClassCastException | NullPointerException | IllegalArgumentException | NoSuchElementException e) {
            LOG.error("Ответ сервиса платежей о сессии заказа {} не разобран", orderId);
            throw new DependencyUnavailableException(PEER, DependencyUnavailableException.SERVER_ERROR, 201, e);
        }
    }
}
