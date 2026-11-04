package dgm.gateway;

import dgm.kit.client.PeerClient;
import dgm.kit.client.PeerRequest;
import dgm.kit.json.Json;
import dgm.kit.trace.TraceContext;
import dgm.kit.trace.TraceContexts;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Вызов {@code checkApiKey} у {@code catalog-service} через клиент каркаса (mTLS с сертификатом шлюза, срок попытки, предохранитель,
 * метрики {@code dgm_client_*}). Запрос идёт POST-ом: хеш в теле, а не в адресе (журнал доступа его не увидит). Повтора нет:
 * вызов без ключа идемпотентности клиент не повторяет, а память шлюза и так сглаживает всплески. Клиент блокирующий, поэтому вызов
 * уходит из потока Netty в отдельный пул, как проверка токена.
 */
public final class CatalogKeyCheck implements ApiKeyVerifier.CatalogCheck {

    static final String PATH = "/internal/v1/api-keys/check";

    private final PeerClient catalog;

    public CatalogKeyCheck(PeerClient catalog) {
        this.catalog = catalog;
    }

    @Override
    public Mono<ApiKeyVerifier.CatalogAnswer> check(String keyHash, TraceContext trace) {
        return Mono.fromCallable(() -> {
            TraceContexts.Scope scope = TraceContexts.open(trace);
            try {
                Map<String, Object> body = catalog.call(PeerRequest.post(PATH, Json.write(Map.of("keyHash", keyHash)), null)).json();
                return answer(body);
            } finally {
                scope.close();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Тело ответа в итог; ответ не того вида это ошибка (проверка недоступна), а не отказ ключу. */
    static ApiKeyVerifier.CatalogAnswer answer(Map<String, Object> body) {
        if (!(body.get("valid") instanceof Boolean valid)) {
            throw new IllegalStateException("В ответе checkApiKey нет признака valid");
        }
        return new ApiKeyVerifier.CatalogAnswer(valid, text(body.get("apiKeyId")), text(body.get("sellerId")), text(body.get("sellerStatus")));
    }

    private static String text(Object value) {
        return value instanceof String string ? string : null;
    }
}
