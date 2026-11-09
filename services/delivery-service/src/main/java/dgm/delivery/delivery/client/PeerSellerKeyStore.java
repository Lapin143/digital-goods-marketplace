package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.KeyStoreResult;
import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.client.PeerClient;
import dgm.kit.client.PeerRequest;
import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SellerKeyStore} поверх {@code PeerClient}: {@code POST /internal/v1/seller-keys} по mTLS (OP-99, вызывает только {@code delivery-service}).
 * Идемпотентность по {@code Idempotency-Key} (новый UUID на вызов, повторы клиента соседа после сбоя несут тот же ключ). Значения ключей уходят
 * только в тело запроса: в журнал, исключения и метрики не попадают. Отказ соседа по существу и недоступность различаются результатом.
 */
public final class PeerSellerKeyStore implements SellerKeyStore {

    /** Имя соседа в {@code dgm.peers.*}. */
    public static final String PEER = "inventory-service";

    private static final Logger LOG = LoggerFactory.getLogger(PeerSellerKeyStore.class);

    /** Текст нарушения, когда сосед не передал свой: поле {@code detail} в ответе продавцу обязательно. */
    static final String MISSING_DETAIL = "Значение поля не принято.";

    private final Supplier<PeerClient> peer;

    public PeerSellerKeyStore(Supplier<PeerClient> peer) {
        this.peer = peer;
    }

    @Override
    public KeyStoreResult store(UUID orderId, UUID requestId, List<String> keys) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId.toString());
        body.put("requestId", requestId.toString());
        body.put("keys", keys);
        try {
            Map<String, Object> json = peer.get().call(PeerRequest.post("/internal/v1/seller-keys", Json.write(body), UUID.randomUUID().toString())).json();
            if (orderId.toString().equals(json.get("orderId")) && json.get("stored") instanceof Number stored && stored.intValue() == keys.size()) {
                return new KeyStoreResult.Stored(stored.intValue());
            }
            LOG.error("Ответ хранилища ключей по запросу {} не по формату", requestId);
            return new KeyStoreResult.Unavailable("seller_keys_response_invalid");
        } catch (DependencyRejectedException e) {
            LOG.warn("Хранилище ключей отказало по запросу {}: код {} ({})", requestId, e.status(), e.problemCode());
            return new KeyStoreResult.Refused(e.status(), e.status() == 422 ? errors(e.json()) : List.of());
        } catch (DependencyUnavailableException e) {
            LOG.warn("Хранилище ключей недоступно для запроса {}: {}", requestId, e.reason());
            return new KeyStoreResult.Unavailable("inventory_unavailable");
        } catch (JsonException e) {
            LOG.error("Ответ хранилища ключей по запросу {} не разобран", requestId);
            return new KeyStoreResult.Unavailable("seller_keys_response_invalid");
        }
    }

    /** Нарушения из ответа 422: только строковые {@code pointer}, {@code code}, {@code detail} (общий текст, если соседа его не передал), ничего лишнего продавцу не передаётся. */
    static List<Map<String, Object>> errors(Map<String, Object> problem) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (problem.get("errors") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> error && error.get("pointer") instanceof String pointer && error.get("code") instanceof String code) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    copy.put("pointer", pointer);
                    copy.put("code", code);
                    // detail обязателен в схеме ошибки поля (components.yaml, FieldError): у соседа его может не быть, продавцу уходит общий текст
                    copy.put("detail", error.get("detail") instanceof String detail ? detail : MISSING_DETAIL);
                    result.add(copy);
                }
            }
        }
        return result;
    }
}
