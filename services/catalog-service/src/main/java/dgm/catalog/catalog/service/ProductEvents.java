package dgm.catalog.catalog.service;

import dgm.catalog.catalog.domain.Moderator;
import dgm.catalog.catalog.domain.Product;
import dgm.catalog.catalog.domain.ProductStatus;
import dgm.kit.outbox.AuditRecords;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Данные событий товара по схемам AsyncAPI ({@code ProductCreatedData}, {@code ProductUpdatedData}, {@code ProductPublishedData},
 * {@code ProductRejectedData}, {@code ProductBlockedData}). Схемы закрыты, лишних полей нет; персональных данных в событиях нет, только
 * идентификаторы, название, тип, способ выдачи, статус, версия и текст решения модератора. Потребитель (реестр товаров
 * {@code inventory-service}) применяет событие, только если версия больше сохранённой.
 */
public final class ProductEvents {

    public static final String TOPIC = "catalog.events";
    public static final String AGGREGATE = "Product";
    public static final String CREATED = "product.created";
    public static final String UPDATED = "product.updated";
    public static final String PUBLISHED = "product.published";
    public static final String REJECTED = "product.rejected";
    public static final String BLOCKED = "product.blocked";
    public static final int SCHEMA_VERSION = 1;

    /** Тип объекта в журнале аудита (ADR-014); действие названо так же, как событие решения. */
    public static final String AUDIT_OBJECT = "product";

    private ProductEvents() {
    }

    /** SM-04/T1: товар создан в черновике. */
    public static Map<String, Object> created(Product product) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", product.id().toString());
        data.put("sellerId", product.sellerId().toString());
        data.put("productType", product.form().productType().code());
        data.put("issuanceMethod", product.form().issuanceMethod().code());
        data.put("status", product.status().code());
        data.put("version", product.version());
        return data;
    }

    /** Изменение полей или статуса: имена изменённых полей вместе с итоговым статусом и версией. */
    public static Map<String, Object> updated(Product product, List<String> changedFields) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", product.id().toString());
        data.put("sellerId", product.sellerId().toString());
        data.put("issuanceMethod", product.form().issuanceMethod().code());
        data.put("status", product.status().code());
        data.put("version", product.version());
        data.put("changedFields", List.copyOf(changedFields));
        return data;
    }

    /** T3: товар опубликован. Письмо продавцу ставит {@code platform-service}, поэтому в событии название. */
    public static Map<String, Object> published(Product product) {
        return decision(product);
    }

    /** T4: товар отклонён с причиной, которую видит продавец. */
    public static Map<String, Object> rejected(Product product) {
        Map<String, Object> data = decision(product);
        data.put("reason", product.rejectionReason());
        return data;
    }

    /** T8: товар заблокирован по решению модератора; {@code cause} отличает его от блокировки вместе с продавцом (R2). */
    public static Map<String, Object> blocked(Product product) {
        Map<String, Object> data = decision(product);
        data.put("reason", product.blockNote());
        data.put("cause", product.blockReason().code());
        return data;
    }

    /**
     * Запись аудита о решении модератора: статус до и после, а для отказа и блокировки ещё и текст причины (причины решений не
     * персональные, ADR-014).
     */
    public static Map<String, Object> audit(Instant at, Moderator moderator, String action, Product before, Product after) {
        List<Map<String, Object>> changes = new ArrayList<>();
        changes.add(AuditRecords.change("status", before.status().code(), after.status().code()));
        String reason = after.status() == ProductStatus.REJECTED ? after.rejectionReason() : after.blockNote();
        if (reason != null) {
            changes.add(AuditRecords.change("decisionReason", null, reason));
        }
        return AuditRecords.staffAction(at, moderator.id(), Moderator.ROLE, moderator.ip(), action, AUDIT_OBJECT, after.id().toString(), changes);
    }

    private static Map<String, Object> decision(Product product) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", product.id().toString());
        data.put("sellerId", product.sellerId().toString());
        data.put("title", product.form().title());
        return data;
    }
}
