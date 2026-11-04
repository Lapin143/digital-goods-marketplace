package dgm.catalog.sellers.integration.controller;

import dgm.catalog.sellers.integration.domain.RotatedSecret;
import dgm.catalog.sellers.integration.domain.SavedIntegration;
import dgm.catalog.sellers.integration.domain.SellerIntegration;
import dgm.kit.time.Timestamps;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Тела ответов по схемам {@code SellerIntegration}, {@code SellerIntegrationSaved}, {@code SecretRotation} и {@code SellerEndpoint} (catalog-service.yaml). */
final class IntegrationViews {

    private IntegrationViews() {
    }

    /**
     * Подключение для кабинета. Пока системы нет, адрес пуст, версии равны нулю, статус {@code disabled}: платформа ничего не вызывает, а нулевая
     * версия это значение первого {@code If-Match}. Секрета в ответе нет никогда.
     */
    static Map<String, Object> integration(Optional<SellerIntegration> found) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (found.isEmpty()) {
            body.put("endpointUrl", null);
            body.put("status", "disabled");
            body.put("secretVersion", 0);
            body.put("version", 0);
            body.put("updatedAt", null);
            return body;
        }
        SellerIntegration integration = found.get();
        body.put("endpointUrl", integration.endpointUrl());
        body.put("status", integration.status().code());
        body.put("secretVersion", integration.secretVersion());
        body.put("version", integration.version());
        body.put("updatedAt", Timestamps.format(integration.updatedAt()));
        return body;
    }

    /** Ответ сохранения: {@code secret} есть, только если подключение создано этим запросом (версия 0 стала 1), иначе поля нет. */
    static Map<String, Object> saved(SavedIntegration saved) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("integration", integration(Optional.of(saved.integration())));
        if (saved.secret() != null) {
            body.put("secret", saved.secret());
        }
        return body;
    }

    /** Ответ смены: со значением ({@code secret} не {@code null}) или без него (повтор по {@code Idempotency-Key}, Р-198). */
    static Map<String, Object> rotation(int secretVersion, String secret) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("secretVersion", secretVersion);
        if (secret != null) {
            body.put("secret", secret);
        }
        body.put("shownOnce", true);
        return body;
    }

    static Map<String, Object> rotation(RotatedSecret rotated) {
        return rotation(rotated.secretVersion(), rotated.secret());
    }

    /** Адрес для вызывающих сервисов: без секрета, его вызывающий считает сам из мастер-секрета. */
    static Map<String, Object> endpoint(SellerIntegration integration) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sellerId", integration.sellerId().toString());
        body.put("endpointUrl", integration.endpointUrl());
        body.put("status", integration.status().code());
        body.put("secretVersion", integration.secretVersion());
        return body;
    }
}
