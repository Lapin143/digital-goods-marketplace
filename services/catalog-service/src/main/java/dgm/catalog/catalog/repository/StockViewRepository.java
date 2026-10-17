package dgm.catalog.catalog.repository;

import java.time.Instant;
import java.util.UUID;

/** Копия остатка для витрины ({@code catalog.stock_view}, INV-09). Источник истины остаётся в {@code inventory-service}. */
public interface StockViewRepository {

    /** Что сделала запись остатка. */
    enum Outcome {
        /** Остаток записан или обновлён. */
        APPLIED,
        /** Событие старше сохранённого: запись не тронута (Р-15). */
        STALE,
        /** Такого товара в каталоге нет: событие не к чему привязать. */
        UNKNOWN_PRODUCT
    }

    /**
     * Записывает число свободных ключей, если метка события не меньше сохранённой. Одним оператором: два события одного товара,
     * обработанные в разных потоках, не перепишут новое значение старым.
     *
     * @param eventTime момент фиксации транзакции издателя ({@code time} конверта)
     */
    Outcome apply(UUID productId, int available, Instant eventTime);
}
