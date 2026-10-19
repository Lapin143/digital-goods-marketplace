package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.ReleasedKeys;
import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.client.PeerClient;
import dgm.kit.client.PeerRequest;
import dgm.kit.json.JsonException;
import dgm.kit.secret.SecretValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link KeyClient} поверх {@code PeerClient}: {@code GET /internal/v1/keys?orderId=…} по mTLS (OP-38, вызывает только {@code delivery-service}).
 * Запрос на чтение идемпотентен, один повтор после сбоя делает клиент соседа. Тело ответа секретное: в журнал, исключения и метрики оно не идёт,
 * значения сразу превращаются в {@link SecretValue}. Любой отказ или ответ не по формату это {@link KeyRequestException} с кодом.
 */
public final class PeerKeyClient implements KeyClient {

    /** Имя соседа в {@code dgm.peers.*}. */
    public static final String PEER = "inventory-service";

    private static final Logger LOG = LoggerFactory.getLogger(PeerKeyClient.class);

    private final Supplier<PeerClient> peer;

    public PeerKeyClient(Supplier<PeerClient> peer) {
        this.peer = peer;
    }

    @Override
    public ReleasedKeys keysOf(UUID orderId) {
        Map<String, Object> json;
        try {
            json = peer.get().call(PeerRequest.get("/internal/v1/keys?orderId=" + orderId)).json();
        } catch (DependencyRejectedException e) {
            LOG.warn("Сервис остатков отказал в ключах заказа {}: код {}", orderId, e.status());
            throw new KeyRequestException(rejectedCode(e.status()), "Сервис остатков отказал в ключах заказа: " + e.status(), e);
        } catch (DependencyUnavailableException e) {
            LOG.warn("Сервис остатков недоступен при запросе ключей заказа {}: {}", orderId, e.reason());
            throw new KeyRequestException("inventory_unavailable", "Сервис остатков недоступен: " + e.reason(), e);
        } catch (JsonException e) {
            LOG.error("Ответ сервиса остатков о ключах заказа {} не разобран", orderId);
            throw new KeyRequestException("keys_response_invalid", "Ответ сервиса остатков не разобран", e);
        }
        return parse(orderId, json);
    }

    private static String rejectedCode(int status) {
        return switch (status) {
            case 404 -> "keys_not_found";
            case 409 -> "keys_not_issued";
            default -> "inventory_rejected_" + status;
        };
    }

    private static ReleasedKeys parse(UUID orderId, Map<String, Object> json) {
        List<SecretValue> values = new ArrayList<>();
        try {
            if (!(json.get("keys") instanceof List<?> keys) || keys.isEmpty() || !orderId.toString().equals(json.get("orderId"))) {
                throw invalid(orderId);
            }
            for (Object item : keys) {
                if (item instanceof Map<?, ?> key && key.get("value") instanceof String value && !value.isEmpty()) {
                    values.add(SecretValue.of(value));
                } else {
                    throw invalid(orderId);
                }
            }
            return new ReleasedKeys(values);
        } catch (KeyRequestException e) {
            for (SecretValue value : values) {
                value.destroy();
            }
            throw e;
        }
    }

    private static KeyRequestException invalid(UUID orderId) {
        LOG.error("Ответ сервиса остатков о ключах заказа {} не по формату", orderId);
        return new KeyRequestException("keys_response_invalid", "Ответ сервиса остатков не по формату", null);
    }
}
