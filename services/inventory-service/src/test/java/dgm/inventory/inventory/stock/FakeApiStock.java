package dgm.inventory.inventory.stock;

import dgm.inventory.inventory.stock.domain.ApiStock;
import dgm.inventory.inventory.stock.repository.ApiStockRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Остаток по API в памяти с теми же правилами, что у SQL: резерв это условное вычитание ({@code quantity >= n}), версия растёт на единицу
 * при каждом изменении (как триггер {@code api_stock_guard}), возврат и правка по версии условные. Единицы в резервах сообщает тест
 * функцией {@link #units}.
 */
public final class FakeApiStock implements ApiStockRepository {

    private static final Instant STAMP = Instant.parse("2026-11-06T12:00:00Z");

    public final Map<UUID, ApiStock> rows = new HashMap<>();
    /** Сумма единиц в резервах по статусу ({@code active}, {@code used}): считает тест по своим резервам. */
    public java.util.function.ToIntFunction<String> units = status -> 0;
    /** Сколько раз вызваны изменения количества: проверка, что повтор события ничего не прибавил. */
    public int changes;

    /** Остаток товара без обращения к методам репозитория. */
    public int quantity(UUID productId) {
        return rows.get(productId).quantity();
    }

    public int version(UUID productId) {
        return rows.get(productId).version();
    }

    @Override
    public boolean ensure(UUID productId, Instant at) {
        if (rows.containsKey(productId)) {
            return false;
        }
        rows.put(productId, new ApiStock(productId, 0, 1, at));
        return true;
    }

    @Override
    public Optional<ApiStock> find(UUID productId) {
        return Optional.ofNullable(rows.get(productId));
    }

    @Override
    public Optional<ApiStock> take(UUID productId, int n) {
        ApiStock row = rows.get(productId);
        if (row == null || row.quantity() < n) {
            return Optional.empty();
        }
        return Optional.of(change(row, row.quantity() - n));
    }

    @Override
    public ApiStock give(UUID productId, int n) {
        ApiStock row = rows.get(productId);
        if (row == null) {
            throw new IllegalStateException("Строки остатка по API нет");
        }
        return change(row, row.quantity() + n);
    }

    @Override
    public Optional<ApiStock> replace(UUID productId, int quantity, int expectedVersion) {
        ApiStock row = rows.get(productId);
        if (row == null || row.version() != expectedVersion) {
            return Optional.empty();
        }
        return Optional.of(change(row, quantity));
    }

    @Override
    public int activeUnits(UUID productId) {
        return units.applyAsInt("active");
    }

    @Override
    public int usedUnits(UUID productId) {
        return units.applyAsInt("used");
    }

    private ApiStock change(ApiStock row, int quantity) {
        if (quantity < 0) {
            throw new IllegalStateException("CHECK (quantity >= 0)");
        }
        ApiStock changed = new ApiStock(row.productId(), quantity, row.version() + 1, STAMP);
        rows.put(row.productId(), changed);
        changes++;
        return changed;
    }
}
