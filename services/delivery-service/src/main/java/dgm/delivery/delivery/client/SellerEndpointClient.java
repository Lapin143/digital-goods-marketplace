package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.SellerEndpoint;
import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.PeerClient;
import dgm.kit.client.PeerRequest;
import dgm.kit.json.JsonException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент подключений продавцов (компонент {@code seller-endpoint-client}): {@code GET /internal/v1/sellers/{sellerId}/integration} у {@code catalog-service}
 * по mTLS (OP-97, {@code getSellerEndpoint}). Ответ запоминается на 30 секунд (шаг 12.2): ряд запросов одному продавцу не бьёт в каталог, а отключённое
 * подключение перестаёт действовать не позже срока памяти. Отсутствие подключения (404) не запоминается. Адрес в журнал не пишется.
 */
public final class SellerEndpointClient implements SellerEndpoints {

    /** Имя соседа в {@code dgm.peers.*}. */
    public static final String PEER = "catalog-service";
    /** Срок памяти об ответе. */
    public static final Duration MEMORY = Duration.ofSeconds(30);

    private static final Logger LOG = LoggerFactory.getLogger(SellerEndpointClient.class);

    private record Remembered(SellerEndpoint endpoint, Instant until) {
    }

    private final Supplier<PeerClient> peer;
    private final Clock clock;
    private final Map<UUID, Remembered> memory = new ConcurrentHashMap<>();

    public SellerEndpointClient(Supplier<PeerClient> peer, Clock clock) {
        this.peer = peer;
        this.clock = clock;
    }

    @Override
    public Optional<SellerEndpoint> find(UUID sellerId) {
        Instant now = clock.instant();
        Remembered known = memory.get(sellerId);
        if (known != null && known.until().isAfter(now)) {
            return Optional.of(known.endpoint());
        }
        Map<String, Object> json;
        try {
            json = peer.get().call(PeerRequest.get("/internal/v1/sellers/" + sellerId + "/integration")).json();
        } catch (DependencyRejectedException e) {
            memory.remove(sellerId);
            if (e.status() == 404) {
                return Optional.empty();
            }
            LOG.warn("Каталог отказал в подключении продавца {}: код {}", sellerId, e.status());
            throw e;
        } catch (JsonException e) {
            LOG.error("Ответ каталога о подключении продавца {} не разобран", sellerId);
            throw new IllegalStateException("Ответ каталога о подключении продавца не разобран", e);
        }
        SellerEndpoint endpoint = parse(sellerId, json);
        memory.put(sellerId, new Remembered(endpoint, now.plus(MEMORY)));
        return Optional.of(endpoint);
    }

    private static SellerEndpoint parse(UUID sellerId, Map<String, Object> json) {
        if (json.get("endpointUrl") instanceof String url && json.get("status") instanceof String status && json.get("secretVersion") instanceof Number version
                && sellerId.toString().equals(json.get("sellerId")) && version.intValue() >= 1) {
            return new SellerEndpoint(url, status, version.intValue());
        }
        LOG.error("Ответ каталога о подключении продавца {} не по формату", sellerId);
        throw new IllegalStateException("Ответ каталога о подключении продавца не по формату");
    }
}
