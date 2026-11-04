package dgm.catalog.sellers.apikey.service;

import dgm.catalog.sellers.apikey.domain.ApiKey;
import dgm.kit.outbox.AuditRecords;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Данные событий {@code api-key.issued} и {@code api-key.revoked} и записи аудита к ним по схемам AsyncAPI ({@code ApiKeyIssuedData},
 * {@code ApiKeyRevokedData}, {@code AuditRecordedData}). Схемы закрыты: в событии только идентификаторы ключа и продавца. Значения ключа, его
 * хеша и имени в событиях и в аудите нет (INV-41, INV-43): в аудите идут статус и открытый отпечаток.
 */
final class ApiKeyEvents {

    static final String TOPIC = "catalog.events";
    static final String AGGREGATE = "ApiKey";
    static final String ISSUED = "api-key.issued";
    static final String REVOKED = "api-key.revoked";
    static final int SCHEMA_VERSION = 1;

    /**
     * Действия в журнале аудита. Код {@code seller.api_key_issued}, а не {@code seller.api-key.issued}: схема {@code AuditRecordedData}
     * допускает в коде только строчные буквы, точку и подчёркивание (как у {@code seller.document_opened}, F4-30).
     */
    static final String AUDIT_ISSUED = "seller.api_key_issued";
    static final String AUDIT_REVOKED = "seller.api_key_revoked";
    static final String AUDIT_OBJECT = "api-key";

    private ApiKeyEvents() {
    }

    static Map<String, Object> event(ApiKey key) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("apiKeyId", key.id().toString());
        data.put("sellerId", key.sellerId().toString());
        return data;
    }

    static Map<String, Object> issuedAudit(Instant at, UUID actorId, ApiKey key) {
        return AuditRecords.sellerAction(at, actorId, AUDIT_ISSUED, AUDIT_OBJECT, key.id().toString(),
                List.of(AuditRecords.change("status", null, key.status().code()), AuditRecords.change("fingerprint", null, key.fingerprint())));
    }

    static Map<String, Object> revokedAudit(Instant at, UUID actorId, ApiKey before, ApiKey after) {
        return AuditRecords.sellerAction(at, actorId, AUDIT_REVOKED, AUDIT_OBJECT, after.id().toString(),
                List.of(AuditRecords.change("status", before.status().code(), after.status().code())));
    }
}
