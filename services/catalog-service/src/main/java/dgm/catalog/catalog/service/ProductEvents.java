package dgm.catalog.catalog.service;

import dgm.catalog.catalog.domain.Product;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Данные событий товара по схемам AsyncAPI ({@code ProductCreatedData}, {@code ProductUpdatedData}). Схемы закрыты, лишних полей нет;
 * персональных данных в событиях нет, только идентификаторы, тип, способ выдачи, статус и версия. Потребитель (реестр товаров
 * {@code inventory-service}) применяет событие, только если версия больше сохранённой.
 */
public final class ProductEvents {

    public static final String TOPIC = "catalog.events";
    public static final String AGGREGATE = "Product";
    public static final String CREATED = "product.created";
    public static final String UPDATED = "product.updated";
    public static final int SCHEMA_VERSION = 1;

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
}
