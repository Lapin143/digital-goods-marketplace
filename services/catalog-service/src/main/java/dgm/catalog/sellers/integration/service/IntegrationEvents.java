package dgm.catalog.sellers.integration.service;

import dgm.catalog.sellers.integration.domain.SellerIntegration;
import dgm.kit.outbox.AuditRecords;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Записи аудита по подключению системы продавца. Домен событий здесь нет: подключение никому в платформе не нужно «по событию», вызывающие
 * сервисы читают его внутренней операцией и держат ответ 30 секунд (OP-97). Схема {@code AuditRecordedData} закрыта, поэтому в записи только
 * статусы, версии и SHA-256 частей адреса: ни секрета, ни мастер-секрета, ни самого адреса (в пути и параметрах продавцы кладут токены).
 */
final class IntegrationEvents {

    /**
     * Действия в журнале. Коды без дефиса: схема допускает строчные буквы, точку и подчёркивание (как у {@code seller.api_key_issued},
     * F4-30).
     */
    static final String AUDIT_SET = "seller.integration_set";
    static final String AUDIT_ROTATED = "seller.integration_secret_rotated";
    static final String AUDIT_OBJECT = "seller-integration";

    private IntegrationEvents() {
    }

    /**
     * Сохранение подключения: первое (до: ничего) или изменение. Часть адреса идёт в запись, только если изменилась; пустой список значит «менять
     * нечего», и вызывающий запись не создаёт.
     */
    static Optional<Map<String, Object>> setAudit(Instant at, UUID actorId, Optional<SellerIntegration> before, SellerIntegration after) {
        List<Map<String, Object>> changes = new ArrayList<>();
        String hostBefore = before.map(b -> hostHash(b.endpointUrl())).orElse(null);
        String hostAfter = hostHash(after.endpointUrl());
        if (!hostAfter.equals(hostBefore)) {
            changes.add(AuditRecords.change("endpointHost", hostBefore, hostAfter));
        }
        String pathBefore = before.map(b -> pathHash(b.endpointUrl())).orElse(null);
        String pathAfter = pathHash(after.endpointUrl());
        if (!pathAfter.equals(pathBefore)) {
            changes.add(AuditRecords.change("endpointPath", pathBefore, pathAfter));
        }
        if (before.isEmpty() || before.get().status() != after.status()) {
            changes.add(AuditRecords.change("status", before.map(b -> b.status().code()).orElse(null), after.status().code()));
        }
        if (before.isEmpty()) {
            changes.add(AuditRecords.change("secretVersion", null, after.secretVersion()));
        }
        if (changes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(AuditRecords.sellerAction(at, actorId, AUDIT_SET, AUDIT_OBJECT, after.sellerId().toString(), changes));
    }

    static Map<String, Object> rotatedAudit(Instant at, UUID actorId, SellerIntegration before, SellerIntegration after) {
        return AuditRecords.sellerAction(at, actorId, AUDIT_ROTATED, AUDIT_OBJECT, after.sellerId().toString(),
                List.of(AuditRecords.change("secretVersion", before.secretVersion(), after.secretVersion())));
    }

    /** SHA-256 имени хоста в нижнем регистре, 64 шестнадцатеричных знака. Адрес уже прошёл проверку охранника, поэтому хост в нём есть. */
    static String hostHash(String endpointUrl) {
        String host = URI.create(endpointUrl).getHost();
        return sha256(host == null ? "" : host.toLowerCase(Locale.ROOT));
    }

    /** SHA-256 пути и параметров адреса: по нему видно, что адрес изменился, не раскрывая токенов в пути. */
    static String pathHash(String endpointUrl) {
        URI uri = URI.create(endpointUrl);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return sha256(path + query);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
