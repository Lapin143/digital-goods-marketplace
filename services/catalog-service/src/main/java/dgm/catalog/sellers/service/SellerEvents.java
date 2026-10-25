package dgm.catalog.sellers.service;

import dgm.catalog.sellers.domain.Moderator;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.kit.outbox.AuditRecords;
import dgm.kit.time.Timestamps;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Данные событий решения по заявке и запись аудита к ним по схемам AsyncAPI ({@code SellerApprovedData}, {@code SellerRejectedData},
 * {@code SellerReturnedData}, {@code AuditRecordedData}). Схемы закрыты, лишних полей нет; персональных данных в событиях нет, только
 * идентификаторы, тип профиля и текст решения модератора.
 */
final class SellerEvents {

    static final String TOPIC = "catalog.events";
    static final String AGGREGATE = "SellerProfile";
    static final String APPROVED = "seller.approved";
    static final String REJECTED = "seller.rejected";
    static final String RETURNED = "seller.returned";
    static final int SCHEMA_VERSION = 1;
    /**
     * Действие в журнале аудита: модератор открыл документ заявки (чтение это действие, ADR-014, Р-166). Название «seller.document_opened», а не
     * «seller-document.opened»: схема {@code AuditRecordedData} допускает в первой части только строчные буквы без дефиса (F4-30).
     */
    static final String DOCUMENT_OPENED = "seller.document_opened";

    /** Тип объекта и коды действий в журнале аудита (ADR-014): действие названо так же, как событие решения. */
    static final String AUDIT_OBJECT = "seller";

    private SellerEvents() {
    }

    static Map<String, Object> approved(SellerProfile profile, Moderator moderator) {
        Map<String, Object> data = base(profile, moderator);
        data.put("sellerType", profile.form().sellerType().code());
        return data;
    }

    static Map<String, Object> rejected(SellerProfile profile, Moderator moderator, Instant nextApplicationAt) {
        Map<String, Object> data = base(profile, moderator);
        data.put("reason", profile.decisionReason());
        data.put("nextApplicationAt", Timestamps.format(nextApplicationAt));
        return data;
    }

    static Map<String, Object> returned(SellerProfile profile, Moderator moderator) {
        Map<String, Object> data = base(profile, moderator);
        data.put("comment", profile.decisionReason());
        return data;
    }

    /**
     * Запись аудита о решении: статус до и после, а для отказа и доработки ещё и текст решения (причины решений не персональные, ADR-014).
     */
    static Map<String, Object> audit(Instant at, Moderator moderator, String action, SellerProfile before, SellerProfile after) {
        List<Map<String, Object>> changes = new ArrayList<>();
        changes.add(AuditRecords.change("status", before.status().code(), after.status().code()));
        if (after.decisionReason() != null) {
            changes.add(AuditRecords.change("decisionReason", before.decisionReason(), after.decisionReason()));
        }
        return AuditRecords.staffAction(at, moderator.id(), Moderator.ROLE, moderator.ip(), action, AUDIT_OBJECT, after.id().toString(), changes);
    }

    /**
     * Запись аудита об одной выданной ссылке на документ: объект это заявка (профиль продавца), в изменении только идентификатор документа
     * (содержимого и самой ссылки нет). Значения «до» нет, «после» это идентификатор: ссылка выдана.
     */
    static Map<String, Object> documentOpened(Instant at, Moderator moderator, UUID sellerId, UUID documentId) {
        return AuditRecords.staffRead(at, moderator.id(), Moderator.ROLE, moderator.ip(), DOCUMENT_OPENED, AUDIT_OBJECT, sellerId.toString(),
                List.of(AuditRecords.change("documentId", null, documentId.toString())));
    }

    private static Map<String, Object> base(SellerProfile profile, Moderator moderator) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sellerId", profile.id().toString());
        data.put("userId", profile.userId().toString());
        data.put("moderatorId", moderator.id().toString());
        return data;
    }
}
