package dgm.catalog.catalog.stock;

import dgm.kit.events.FatalEventException;
import java.util.Map;
import java.util.UUID;

/**
 * Нужная часть данных события {@code stock.changed} ({@code StockChangedData}): товар и число свободных ключей. Нарушение схемы
 * неисправимо, повтор события его не изменит, поэтому сразу уходит в DLQ. Сообщения об ошибках называют поле, но не значение.
 *
 * @param productId товар
 * @param available число свободных ключей, не меньше нуля
 */
public record StockChange(UUID productId, int available) {

    static StockChange parse(Map<String, Object> data) {
        if (!(data.get("productId") instanceof String id) || id.length() != 36) {
            throw new FatalEventException("Поле productId обязательно и должно быть UUID");
        }
        UUID productId;
        try {
            productId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new FatalEventException("Поле productId должно быть UUID");
        }
        Object raw = data.get("available");
        boolean whole = raw instanceof Integer || raw instanceof Long;
        if (!whole || ((Number) raw).longValue() < 0 || ((Number) raw).longValue() > Integer.MAX_VALUE) {
            throw new FatalEventException("Поле available обязательно и должно быть целым числом от нуля");
        }
        return new StockChange(productId, ((Number) raw).intValue());
    }
}
