package dgm.inventory.inventory.stock.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Остаток товара с выдачей по API (Р-183, ADR-008): сколько единиц продавец готов выдать сейчас. Единицы в активных и использованных
 * резервах из остатка уже вычтены, а возвращаются в него при снятии резерва. Версия растёт на единицу при каждом изменении количества
 * (её ведёт триггер базы) и служит {@code ETag} для {@code If-Match} в {@code setApiStock}.
 *
 * @param productId товар с {@code issuance_method = seller_api}
 * @param quantity  остаток, доступный к заказу, от нуля
 * @param version   версия записи, от единицы
 * @param updatedAt последнее изменение количества
 */
public record ApiStock(UUID productId, int quantity, int version, Instant updatedAt) {

    /** Верхняя граница остатка, которую продавец может задать (схема {@code ApiStockRequest}, 1 000 000). */
    public static final int MAX_QUANTITY = 1_000_000;
}
