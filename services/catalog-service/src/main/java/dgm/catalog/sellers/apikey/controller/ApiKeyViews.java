package dgm.catalog.sellers.apikey.controller;

import dgm.catalog.sellers.apikey.domain.ApiKey;
import dgm.catalog.sellers.apikey.domain.ApiKeyPage;
import dgm.catalog.sellers.apikey.domain.ApiKeyStatus;
import dgm.catalog.sellers.apikey.domain.KeyCheck;
import dgm.kit.time.Timestamps;
import dgm.kit.web.PageCursor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Тела ответов по схемам {@code ApiKey}, {@code ApiKeyIssued}, {@code ApiKeyPage} и {@code ApiKeyCheck} (catalog-service.yaml). */
final class ApiKeyViews {

    private ApiKeyViews() {
    }

    /** Ключ без значения: поля схемы {@code ApiKey}, {@code revokedAt} есть всегда и у действующего ключа равно {@code null}. */
    static Map<String, Object> key(ApiKey key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", key.id().toString());
        body.put("name", key.name());
        body.put("fingerprint", key.fingerprint());
        body.put("status", key.status().code());
        body.put("createdAt", time(key.createdAt()));
        body.put("revokedAt", time(key.revokedAt()));
        return body;
    }

    /** Ответ выпуска: со значением ({@code value} не {@code null}) или без него (повтор по {@code Idempotency-Key}, Р-198). */
    static Map<String, Object> issued(ApiKey key, String value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiKey", key(key));
        if (value != null) {
            body.put("value", value);
        }
        body.put("shownOnce", true);
        return body;
    }

    static Map<String, Object> page(ApiKeyPage page) {
        List<Object> items = new ArrayList<>();
        for (ApiKey key : page.items()) {
            items.add(key(key));
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("limit", page.limit());
        info.put("nextCursor", page.next().map(n -> PageCursor.encode(Map.of("c", n.createdAt().toString(), "i", n.id().toString()))).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", info);
        return body;
    }

    /** Ответ проверки шлюзу: неизвестный и отозванный ключ дают один и тот же ответ без причины. */
    static Map<String, Object> check(Optional<KeyCheck> found) {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean valid = found.isPresent() && found.get().keyStatus() == ApiKeyStatus.ACTIVE;
        body.put("valid", valid);
        body.put("apiKeyId", valid ? found.get().apiKeyId().toString() : null);
        body.put("sellerId", valid ? found.get().sellerId().toString() : null);
        body.put("sellerStatus", valid ? found.get().sellerStatus().code() : null);
        return body;
    }

    private static String time(Instant instant) {
        return instant == null ? null : Timestamps.format(instant);
    }
}
