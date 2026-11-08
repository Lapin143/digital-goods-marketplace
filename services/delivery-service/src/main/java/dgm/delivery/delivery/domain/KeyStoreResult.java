package dgm.delivery.delivery.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Итог передачи ключей продавца в хранилище ключей ({@code storeSellerKeys}, OP-99). Значений ключей здесь нет: ни в итоге, ни в списке нарушений
 * (в нём только позиции и коды).
 */
public sealed interface KeyStoreResult {

    /** Ключи приняты и выданы по заказу (или это повтор тех же ключей). */
    record Stored(int count) implements KeyStoreResult {
    }

    /**
     * Хранилище отказало по существу: {@code 409} резерв не подтверждён оплатой или снят, {@code 422} число ключей, дубликат или формат, остальное
     * (404, 403) это нарушение согласованности.
     *
     * @param status код ответа
     * @param errors нарушения {@code pointer, code, detail} для ответа продавцу (только при 422)
     */
    record Refused(int status, List<Map<String, Object>> errors) implements KeyStoreResult {

        public Refused {
            errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        }
    }

    /** Хранилище недоступно или ответило не по формату: ключи не потеряны, продавец повторит вызов. */
    record Unavailable(String code) implements KeyStoreResult {
    }
}
