package dgm.inventory.inventory.keys.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Остаток товара (схема {@code StockInfo}, SM-03). У товара с пулом остаток это число свободных ключей, выданный ключ в остаток не
 * возвращается; аннулированных ключей в R1 нет, поэтому размер пула это сумма свободных, зарезервированных и выданных. У товара с выдачей по
 * API (R2, Р-183) пула нет: {@code available} остаток по API, {@code reserved} единицы в активных резервах, {@code issued} единицы в
 * использованных, а {@code issuanceMethod} и {@code version} заполнены, {@code poolLimit} нет.
 *
 * @param productId      товар
 * @param available      остаток, доступный к заказу
 * @param reserved       в резерве под неоплаченные заказы
 * @param issued         выдано (у товара по API оплаченные единицы)
 * @param poolLimit      предел пула; у товара по API 0 и в ответ не идёт
 * @param updatedAt      момент ответа (у товара по API последнее изменение количества)
 * @param issuanceMethod {@code seller_api} у товара по API, иначе пусто
 * @param version        версия остатка по API для {@code If-Match}; у товара с пулом пусто
 */
public record StockInfo(UUID productId, int available, int reserved, int issued, int poolLimit, Instant updatedAt, String issuanceMethod,
                        Integer version) {

    /** Остаток товара с выдачей по API. */
    public static StockInfo api(UUID productId, int available, int reserved, int issued, int version, Instant updatedAt) {
        return new StockInfo(productId, available, reserved, issued, 0, updatedAt, "seller_api", version);
    }

    public boolean sellerApi() {
        return issuanceMethod != null;
    }

    /** Размер пула: сумма свободных, зарезервированных и выданных ключей (только у товара с пулом). */
    public int poolSize() {
        return available + reserved + issued;
    }
}
