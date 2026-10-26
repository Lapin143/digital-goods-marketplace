package dgm.platform.support.client;

import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.PeerClient;
import dgm.kit.client.PeerRequest;
import dgm.kit.json.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link OrderClient} поверх {@code PeerClient}: {@code POST /internal/v1/orders/{orderId}/update-delivery-address} по mTLS (OP-72, вызывает только
 * {@code platform-service}). У запроса есть ключ идемпотентности, поэтому клиент соседа повторяет его после сбоя сам, а сосед по ключу вернёт прежний
 * результат. Тело запроса и ответа несут адрес (персональные данные): в журнал, исключения и метрики они не идут.
 */
public final class PeerOrderClient implements OrderClient {

    /** Имя соседа в {@code dgm.peers.*}. */
    public static final String PEER = "order-service";

    private static final Logger LOG = LoggerFactory.getLogger(PeerOrderClient.class);

    private final Supplier<PeerClient> peer;

    public PeerOrderClient(Supplier<PeerClient> peer) {
        this.peer = peer;
    }

    @Override
    public void updateDeliveryAddress(UUID orderId, UUID ticketId, String email, UUID idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", ticketId.toString());
        body.put("email", email);
        try {
            peer.get().call(PeerRequest.post("/internal/v1/orders/" + orderId + "/update-delivery-address", Json.write(body), idempotencyKey.toString()));
        } catch (DependencyRejectedException e) {
            LOG.warn("Сервис заказов отказал в обновлении адреса заказа {} по обращению {}: код {} {}", orderId, ticketId, e.status(), e.problemCode());
            throw new OrderRefusedException(e.status(), e.problemCode());
        }
    }
}
