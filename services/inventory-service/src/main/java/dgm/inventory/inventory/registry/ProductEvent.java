package dgm.inventory.inventory.registry;

import dgm.kit.events.FatalEventException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Нужная реестру часть данных событий каталога ({@code product.created}, {@code product.updated}, {@code product.published},
 * {@code product.rejected}, {@code product.blocked}). Нарушение схемы неисправимо: повтор события его не изменит, поэтому оно сразу уходит
 * в DLQ. Сообщения об ошибках называют поле, но не его значение.
 */
final class ProductEvent {

    private static final Set<String> STATUSES = Set.of("draft", "on_moderation", "published", "rejected", "blocked", "archived");
    private static final Set<String> ISSUANCE = Set.of("key_pool", "seller_api");

    private ProductEvent() {
    }

    static UUID productId(Map<String, Object> data) {
        return uuid(data, "productId");
    }

    /** Копия товара из события с версией ({@code product.created}, {@code product.updated}). */
    static ProductCopy copy(Map<String, Object> data) {
        String status = text(data, "status", STATUSES);
        String issuance = text(data, "issuanceMethod", ISSUANCE);
        Object version = data.get("version");
        if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() < 1
                || ((Number) version).longValue() > Integer.MAX_VALUE) {
            throw new FatalEventException("Поле version обязательно и должно быть целым числом от единицы");
        }
        return new ProductCopy(productId(data), uuid(data, "sellerId"), issuance, status, ((Number) version).intValue());
    }

    private static UUID uuid(Map<String, Object> data, String field) {
        if (!(data.get(field) instanceof String text) || text.length() != 36) {
            throw new FatalEventException("Поле " + field + " обязательно и должно быть UUID");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new FatalEventException("Поле " + field + " должно быть UUID");
        }
    }

    private static String text(Map<String, Object> data, String field, Set<String> allowed) {
        if (!(data.get(field) instanceof String text) || !allowed.contains(text)) {
            throw new FatalEventException("Поле " + field + " обязательно и должно быть одним из допустимых значений");
        }
        return text;
    }
}
