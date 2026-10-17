package dgm.inventory.inventory.keys.repository;

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

    /** Число ключей по статусам: {@code free}, {@code reserved}, {@code issued}, {@code voided}; статусов без ключей в ответе нет. */
    Map<String, Integer> countByStatus(UUID productId);

    /**
     * Вставляет ключи, не трогая уже имеющиеся значения: {@code INSERT ... ON CONFLICT (product_id, hmac) DO NOTHING RETURNING hmac}.
     *
     * @return HMAC (шестнадцатеричная запись) вставленных ключей; остальные строки это дубли в пуле
     */
    Set<String> insertNew(UUID productId, List<NewKey> keys);
}
