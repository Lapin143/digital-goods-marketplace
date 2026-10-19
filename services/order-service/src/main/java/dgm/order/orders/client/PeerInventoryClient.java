package dgm.order.orders.client;

import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.client.PeerClient;
import dgm.kit.client.PeerRequest;
import dgm.kit.idempotency.IdempotencyKeys;
import dgm.kit.json.Json;
import dgm.kit.json.JsonException;
import dgm.kit.time.Timestamps;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link InventoryClient} поверх {@code PeerClient}: {@code POST /internal/v1/reservations} по mTLS с ключом идемпотентности, выведенным из
 * шага и заказа. Ответ 409 {@code insufficient-stock}, 404 и 409 {@code product-unavailable} это исходы по смыслу, остальные отказы считаются недоступностью соседа.
 */
public final class PeerInventoryClient implements InventoryClient {

    /** Имя соседа в {@code dgm.peers.*}. */
    public static final String PEER = "inventory-service";

    /** Имя шага для ключа идемпотентности (Р-149). */
    public static final String STEP = "reserve";

    /** Имя шага «подтвердить резерв»: ключ отличается от ключа резерва того же заказа (Р-149). */
    public static final String CONFIRM_STEP = "confirm";

    private static final Logger LOG = LoggerFactory.getLogger(PeerInventoryClient.class);

    private final Supplier<PeerClient> peer;

    public PeerInventoryClient(Supplier<PeerClient> peer) {
        this.peer = peer;
    }

    @Override
    public ReserveResult reserve(UUID orderId, UUID productId, int quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId.toString());
        body.put("productId", productId.toString());
        body.put("quantity", quantity);
        try {
            Map<String, Object> json = peer.get()
                    .call(PeerRequest.post("/internal/v1/reservations", Json.write(body), IdempotencyKeys.derived(STEP, orderId).toString())).json();
            return new Reserved(UUID.fromString((String) json.get("reservationId")), Timestamps.parse((String) json.get("expiresAt")).orElseThrow());
        } catch (DependencyRejectedException e) {
            if (e.status() == 409 && "insufficient-stock".equals(e.problemCode())) {
                return new OutOfStock(available(e));
            }
            if (e.status() == 404 || (e.status() == 409 && "product-unavailable".equals(e.problemCode()))) {
                return new UnknownProduct();
            }
            LOG.error("Сервис остатков отклонил резерв заказа {}: код {} {}", orderId, e.status(), e.problemCode());
            throw new DependencyUnavailableException(PEER, DependencyUnavailableException.SERVER_ERROR, e.status(), e);
        } catch (JsonException | ClassCastException | NullPointerException | IllegalArgumentException | java.util.NoSuchElementException e) {
            LOG.error("Ответ сервиса остатков о резерве заказа {} не разобран", orderId);
            throw new DependencyUnavailableException(PEER, DependencyUnavailableException.SERVER_ERROR, 201, e);
        }
    }

    @Override
    public ConfirmResult confirm(UUID orderId, UUID productId, int quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId.toString());
        body.put("productId", productId.toString());
        body.put("quantity", quantity);
        try {
            Map<String, Object> json = peer.get().call(PeerRequest.post("/internal/v1/reservations/confirm", Json.write(body),
                    IdempotencyKeys.derived(CONFIRM_STEP, orderId).toString())).json();
            return new Confirmed(UUID.fromString((String) json.get("reservationId")), Boolean.TRUE.equals(json.get("reReserved")));
        } catch (DependencyRejectedException e) {
            if (e.status() == 409 && "insufficient-stock".equals(e.problemCode())) {
                return new NoKeys(available(e));
            }
            if (e.status() == 404) {
                LOG.warn("Сервис остатков не нашёл резерва заказа {} при подтверждении", orderId);
                return new NoKeys(0);
            }
            LOG.error("Сервис остатков отклонил подтверждение резерва заказа {}: код {} {}", orderId, e.status(), e.problemCode());
            throw new DependencyUnavailableException(PEER, DependencyUnavailableException.SERVER_ERROR, e.status(), e);
        } catch (JsonException | ClassCastException | NullPointerException | IllegalArgumentException e) {
            LOG.error("Ответ сервиса остатков о подтверждении резерва заказа {} не разобран", orderId);
            throw new DependencyUnavailableException(PEER, DependencyUnavailableException.SERVER_ERROR, 200, e);
        }
    }

    private static int available(DependencyRejectedException e) {
        return e.json().get("available") instanceof Number n ? Math.max(0, n.intValue()) : 0;
    }
}
