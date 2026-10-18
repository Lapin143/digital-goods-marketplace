package dgm.inventory.inventory.keys.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Ключи пула в {@code inventory.key}. Значений в открытом виде здесь нет: только шифртекст и HMAC. */
public interface KeyRepository {

    /**
     * Строка новой записи ключа.
     *
     * @param id         идентификатор ключа (входит в AAD шифрования)
     * @param dekId      версия ключа данных
     * @param nonce      96 бит
     * @param ciphertext шифртекст с тегом
     * @param hmac       HMAC значения для товара
     */
    record NewKey(UUID id, int dekId, byte[] nonce, byte[] ciphertext, byte[] hmac) {
    }

    /**
     * Ключ заказа вместе с шифртекстом: для выдачи значений. Открытого значения здесь нет.
     *
     * @param id         идентификатор ключа (входит в AAD шифрования)
     * @param productId  товар (входит в AAD шифрования)
     * @param status     статус ключа
     * @param dekId      версия ключа данных
     * @param nonce      96 бит
     * @param ciphertext шифртекст с тегом
     */
    record StoredKey(UUID id, UUID productId, String status, int dekId, byte[] nonce, byte[] ciphertext) {
    }

    /** Число ключей по статусам: {@code free}, {@code reserved}, {@code issued}, {@code voided}; статусов без ключей в ответе нет. */
    Map<String, Integer> countByStatus(UUID productId);

    /**
     * Закрепляет за заказом {@code quantity} свободных ключей товара в порядке идентификаторов: одним запросом
     * {@code WITH picked AS MATERIALIZED (SELECT … FOR UPDATE SKIP LOCKED) UPDATE … SET status = 'reserved' RETURNING id} (ADR-007, SM-03/T2).
     * Строки, заблокированные другой транзакцией, пропускаются, а не ожидаются. Вызывать в транзакции, где резерв уже создан.
     *
     * @return идентификаторы закреплённых ключей; их может быть меньше {@code quantity}, если свободных не хватило, тогда
     *         транзакцию надо откатить (частичного резерва нет, INV-05)
     */
    List<UUID> reserveFree(UUID productId, int quantity, UUID orderId, UUID reservationId);

    /**
     * Возвращает ключи резерва в свободные (SM-03/T3): {@code reserved → free}, привязка к заказу и резерву снимается. Выданные
     * ключи не трогаются.
     *
     * @return сколько ключей возвращено
     */
    int releaseByReservation(UUID reservationId);

    /**
     * Выдаёт ключи резерва после подтверждённой оплаты (SM-03/T4): {@code reserved → issued}, ключи остаются за заказом и резервом.
     * Выданный ключ в свободные не возвращается (INV-04).
     *
     * @return сколько ключей выдано
     */
    int issueByReservation(UUID reservationId, Instant at);

    /** Все ключи заказа в порядке выдачи (по времени выдачи, затем по идентификатору), в любом статусе. Пусто, если у заказа ключей нет. */
    List<StoredKey> byOrder(UUID orderId);

    /**
     * Вставляет ключи, не трогая уже имеющиеся значения: {@code INSERT ... ON CONFLICT (product_id, hmac) DO NOTHING RETURNING hmac}.
     *
     * @return HMAC (шестнадцатеричная запись) вставленных ключей; остальные строки это дубли в пуле
     */
    Set<String> insertNew(UUID productId, List<NewKey> keys);
}
