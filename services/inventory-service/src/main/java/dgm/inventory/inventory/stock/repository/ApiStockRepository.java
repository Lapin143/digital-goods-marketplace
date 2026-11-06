package dgm.inventory.inventory.stock.repository;

import dgm.inventory.inventory.stock.domain.ApiStock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Остаток товаров с выдачей по API в {@code inventory.api_stock} (ADR-008). Изменения количества это один условный {@code UPDATE}
 * без явных блокировок: он заменяет {@code SELECT … FOR UPDATE}. Версию записи и время изменения ставит триггер базы, поэтому в запросах
 * они не пишутся. Вызывать в транзакции модуля; строка товара ({@code ProductCopyRepository.lock}) блокируется вызывающим первой, как в
 * остальных путях, поэтому порядок блокировок один и тот же и версии остатка идут в порядке фиксации.
 */
public interface ApiStockRepository {

    /**
     * Создаёт строку остатка с нулевым количеством, если её нет: {@code INSERT … ON CONFLICT DO NOTHING}. Копия товара с выдачей по API
     * к этому моменту уже записана в той же транзакции (триггер базы проверяет способ выдачи).
     *
     * @return {@code true}, если строка создана этим вызовом
     */
    boolean ensure(UUID productId, Instant at);

    Optional<ApiStock> find(UUID productId);

    /**
     * Резервирует количество: {@code UPDATE … SET quantity = quantity - :n WHERE product_id = :p AND quantity >= :n RETURNING …}. Ноль
     * строк означает «не хватает» (ничего не изменено), {@code CHECK (quantity >= 0)} страхует код (INV-09).
     *
     * @return остаток после резерва; пусто, если остатка меньше {@code n} или строки нет
     */
    Optional<ApiStock> take(UUID productId, int n);

    /**
     * Возвращает количество в остаток: {@code UPDATE … SET quantity = quantity + :n WHERE product_id = :p RETURNING …}. Вызывать только
     * после условного перехода резерва «если активен» (или «если использован» для T5): повторное событие тогда ничего не прибавит.
     *
     * @return остаток после возврата
     * @throws IllegalStateException строки остатка нет: товар по API без неё не бывает
     */
    ApiStock give(UUID productId, int n);

    /**
     * Заменяет количество, если версия записи та, что продавец видел: {@code UPDATE … SET quantity = :q WHERE product_id = :p AND version = :v
     * RETURNING …} (ADR-008).
     *
     * @return запись после правки; пусто, если версия устарела или строки нет
     */
    Optional<ApiStock> replace(UUID productId, int quantity, int expectedVersion);

    /** Сколько единиц в активных резервах товара: показывает {@code getProductStock} (поле {@code reserved}). */
    int activeUnits(UUID productId);

    /** Сколько единиц в использованных резервах товара, то есть оплачено и выдаётся продавцом: поле {@code issued}. */
    int usedUnits(UUID productId);
}
