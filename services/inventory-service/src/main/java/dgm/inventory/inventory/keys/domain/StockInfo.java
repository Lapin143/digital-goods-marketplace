package dgm.inventory.inventory.keys.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Остаток товара с пулом по статусам ключей (схема {@code StockInfo}, SM-03). Остаток это число свободных ключей, выданный ключ в остаток
 * не возвращается. Аннулированных ключей в R1 нет, поэтому размер пула это сумма свободных, зарезервированных и выданных.
 */
public record StockInfo(UUID productId, int available, int reserved, int issued, int poolLimit, Instant updatedAt) {

    public int poolSize() {
        return available + reserved + issued;
    }
}
